/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.gradle.tasks.bundling;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import org.gradle.api.Action;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.junit.jupiter.api.Test;

import org.springframework.boot.loader.tools.layer.application.FilteredResourceStrategy;
import org.springframework.boot.loader.tools.layer.application.LocationFilter;
import org.springframework.boot.loader.tools.layer.library.CoordinateFilter;
import org.springframework.boot.loader.tools.layer.library.FilteredLibraryStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link BootJar}.
 *
 * @author Andy Wilkinson
 * @author Madhura Bhave
 * @author Scott Frederick
 */
class BootJarTests extends AbstractBootArchiveTests<BootJar> {

	BootJarTests() {
		super(BootJar.class, "org.springframework.boot.loader.JarLauncher", "BOOT-INF/lib/", "BOOT-INF/classes/");
	}

	@Test
	void contentCanBeAddedToBootInfUsingCopySpecFromGetter() throws IOException {
		BootJar bootJar = getTask();
		bootJar.setMainClassName("com.example.Application");
		bootJar.getBootInf().into("test").from(new File("build.gradle").getAbsolutePath());
		bootJar.copy();
		try (JarFile jarFile = new JarFile(bootJar.getArchiveFile().get().getAsFile())) {
			assertThat(jarFile.getJarEntry("BOOT-INF/test/build.gradle")).isNotNull();
		}
	}

	@Test
	void contentCanBeAddedToBootInfUsingCopySpecAction() throws IOException {
		BootJar bootJar = getTask();
		bootJar.setMainClassName("com.example.Application");
		bootJar.bootInf((copySpec) -> copySpec.into("test").from(new File("build.gradle").getAbsolutePath()));
		bootJar.copy();
		try (JarFile jarFile = new JarFile(bootJar.getArchiveFile().get().getAsFile())) {
			assertThat(jarFile.getJarEntry("BOOT-INF/test/build.gradle")).isNotNull();
		}
	}

	@Test
	void whenJarIsLayeredThenBootInfContainsOnlyLayersAndIndexFiles() throws IOException {
		List<String> entryNames = getEntryNames(createLayeredJar());
		assertThat(entryNames.stream().filter((name) -> name.startsWith("BOOT-INF/"))
				.filter((name) -> !name.startsWith("BOOT-INF/layers/"))).contains("BOOT-INF/layers.idx",
						"BOOT-INF/classpath.idx");
	}

	@Test
	void whenJarIsLayeredThenManifestContainsEntryForLayersIndexInPlaceOfClassesAndLib() throws IOException {
		try (JarFile jarFile = new JarFile(createLayeredJar())) {
			assertThat(jarFile.getManifest().getMainAttributes().getValue("Spring-Boot-Classes")).isEqualTo(null);
			assertThat(jarFile.getManifest().getMainAttributes().getValue("Spring-Boot-Lib")).isEqualTo(null);
			assertThat(jarFile.getManifest().getMainAttributes().getValue("Spring-Boot-Layers-Index"))
					.isEqualTo("BOOT-INF/layers.idx");
		}
	}

	@Test
	void whenJarIsLayeredThenLayersIndexIsPresentAndListsLayersInOrder() throws IOException {
		try (JarFile jarFile = new JarFile(createLayeredJar())) {
			assertThat(entryLines(jarFile, "BOOT-INF/layers.idx")).containsExactly("dependencies",
					"snapshot-dependencies", "resources", "application");
		}
	}

	@Test
	void whenJarIsLayeredThenContentsAreMovedToLayerDirectories() throws IOException {
		List<String> entryNames = getEntryNames(createLayeredJar());
		assertThat(entryNames)
				.containsSubsequence("BOOT-INF/layers/dependencies/lib/first-library.jar",
						"BOOT-INF/layers/dependencies/lib/second-library.jar")
				.contains("BOOT-INF/layers/snapshot-dependencies/lib/third-library-SNAPSHOT.jar")
				.containsSubsequence("BOOT-INF/layers/application/classes/com/example/Application.class",
						"BOOT-INF/layers/application/classes/application.properties")
				.contains("BOOT-INF/layers/resources/classes/static/test.css");
	}

	@Test
	void whenJarIsLayeredWithCustomStrategiesThenContentsAreMovedToLayerDirectories() throws IOException {
		File jar = createLayeredJar((configuration) -> {
			configuration.layers("my-deps", "my-internal-deps", "my-snapshot-deps", "resources", "application");
			configuration.libraries(createLibraryStrategy("my-snapshot-deps", "com.example:*:*.SNAPSHOT"),
					createLibraryStrategy("my-internal-deps", "com.example:*:*"),
					createLibraryStrategy("my-deps", "*:*"));
			configuration.classes(createResourceStrategy("resources", "static/**"),
					createResourceStrategy("application", "**"));
		});
		List<String> entryNames = getEntryNames(jar);
		assertThat(entryNames)
				.containsSubsequence("BOOT-INF/layers/my-internal-deps/lib/first-library.jar",
						"BOOT-INF/layers/my-internal-deps/lib/second-library.jar")
				.contains("BOOT-INF/layers/my-snapshot-deps/lib/third-library-SNAPSHOT.jar")
				.containsSubsequence("BOOT-INF/layers/application/classes/com/example/Application.class",
						"BOOT-INF/layers/application/classes/application.properties")
				.contains("BOOT-INF/layers/resources/classes/static/test.css");
	}

	private FilteredLibraryStrategy createLibraryStrategy(String layerName, String... includes) {
		return new FilteredLibraryStrategy(layerName,
				Collections.singletonList(new CoordinateFilter(Arrays.asList(includes), Collections.emptyList())));
	}

	private FilteredResourceStrategy createResourceStrategy(String layerName, String... includes) {
		return new FilteredResourceStrategy(layerName,
				Collections.singletonList(new LocationFilter(Arrays.asList(includes), Collections.emptyList())));
	}

	@Test
	void whenJarIsLayeredJarsInLibAreStored() throws IOException {
		try (JarFile jarFile = new JarFile(createLayeredJar())) {
			assertThat(jarFile.getEntry("BOOT-INF/layers/dependencies/lib/first-library.jar").getMethod())
					.isEqualTo(ZipEntry.STORED);
			assertThat(jarFile.getEntry("BOOT-INF/layers/dependencies/lib/second-library.jar").getMethod())
					.isEqualTo(ZipEntry.STORED);
			assertThat(jarFile.getEntry("BOOT-INF/layers/snapshot-dependencies/lib/third-library-SNAPSHOT.jar")
					.getMethod()).isEqualTo(ZipEntry.STORED);
		}
	}

	@Test
	void whenJarIsLayeredClasspathIndexPointsToLayeredLibs() throws IOException {
		try (JarFile jarFile = new JarFile(createLayeredJar())) {
			assertThat(entryLines(jarFile, "BOOT-INF/classpath.idx")).containsExactly(
					"BOOT-INF/layers/dependencies/lib/first-library.jar",
					"BOOT-INF/layers/dependencies/lib/second-library.jar",
					"BOOT-INF/layers/snapshot-dependencies/lib/third-library-SNAPSHOT.jar");
		}
	}

	@Test
	void whenJarIsLayeredThenLayerToolsAreAddedToTheJar() throws IOException {
		List<String> entryNames = getEntryNames(createLayeredJar());
		assertThat(entryNames).contains("BOOT-INF/layers/dependencies/lib/spring-boot-jarmode-layertools.jar");
	}

	@Test
	void whenJarIsLayeredAndIncludeLayerToolsIsFalseThenLayerToolsAreNotAddedToTheJar() throws IOException {
		List<String> entryNames = getEntryNames(
				createLayeredJar((configuration) -> configuration.setIncludeLayerTools(false)));
		assertThat(entryNames).doesNotContain("BOOT-INF/layers/dependencies/lib/spring-boot-jarmode-layertools.jar");
	}

	@Test
	void classpathIndexPointsToBootInfLibs() throws IOException {
		try (JarFile jarFile = new JarFile(createPopulatedJar())) {
			assertThat(jarFile.getManifest().getMainAttributes().getValue("Spring-Boot-Classpath-Index"))
					.isEqualTo("BOOT-INF/classpath.idx");
			assertThat(entryLines(jarFile, "BOOT-INF/classpath.idx")).containsExactly("BOOT-INF/lib/first-library.jar",
					"BOOT-INF/lib/second-library.jar", "BOOT-INF/lib/third-library-SNAPSHOT.jar");
		}
	}

	private File createPopulatedJar() throws IOException {
		addContent();
		executeTask();
		return getTask().getArchiveFile().get().getAsFile();
	}

	private File createLayeredJar(Action<LayerConfiguration> action) throws IOException {
		if (action != null) {
			getTask().layers(action);
		}
		else {
			getTask().layers();
		}
		addContent();
		executeTask();
		return getTask().getArchiveFile().get().getAsFile();
	}

	private File createLayeredJar() throws IOException {
		return createLayeredJar(null);
	}

	private void addContent() throws IOException {
		BootJar bootJar = getTask();
		bootJar.setMainClassName("com.example.Main");
		File classesJavaMain = new File(this.temp, "classes/java/main");
		File applicationClass = new File(classesJavaMain, "com/example/Application.class");
		applicationClass.getParentFile().mkdirs();
		applicationClass.createNewFile();
		File resourcesMain = new File(this.temp, "resources/main");
		File applicationProperties = new File(resourcesMain, "application.properties");
		applicationProperties.getParentFile().mkdirs();
		applicationProperties.createNewFile();
		File staticResources = new File(resourcesMain, "static");
		staticResources.mkdir();
		File css = new File(staticResources, "test.css");
		css.createNewFile();
		bootJar.classpath(classesJavaMain, resourcesMain, jarFile("first-library.jar"), jarFile("second-library.jar"),
				jarFile("third-library-SNAPSHOT.jar"));

		Set<ResolvedArtifactResult> resolvedArtifacts = new HashSet<>();
		resolvedArtifacts.add(mockLibraryArtifact("first-library.jar", "com.example:first-library:1.0.0"));
		resolvedArtifacts.add(mockLibraryArtifact("second-library.jar", "com.example:second-library:1.0.0"));
		resolvedArtifacts
				.add(mockLibraryArtifact("third-library-SNAPSHOT.jar", "com.example:third-library:1.0.0.SNAPSHOT"));

		ArtifactCollection artifacts = mock(ArtifactCollection.class);
		given(artifacts.getArtifacts()).willReturn(resolvedArtifacts);

		ResolvableDependencies deps = mock(ResolvableDependencies.class);
		given(deps.getArtifacts()).willReturn(artifacts);
		bootJar.resolvedDependencies(deps);
	}

	private ResolvedArtifactResult mockLibraryArtifact(String fileName, String coordinates) {
		ComponentIdentifier libraryId = mock(ComponentIdentifier.class);
		given(libraryId.getDisplayName()).willReturn(coordinates);

		ComponentArtifactIdentifier libraryArtifactId = mock(ComponentArtifactIdentifier.class);
		given(libraryArtifactId.getComponentIdentifier()).willReturn(libraryId);

		ResolvedArtifactResult libraryArtifact = mock(ResolvedArtifactResult.class);
		given(libraryArtifact.getFile()).willReturn(new File(fileName));
		given(libraryArtifact.getId()).willReturn(libraryArtifactId);

		return libraryArtifact;
	}

	private List<String> entryLines(JarFile jarFile, String entryName) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(jarFile.getInputStream(jarFile.getEntry(entryName))))) {
			return reader.lines().collect(Collectors.toList());
		}
	}

	@Override
	protected void executeTask() {
		getTask().copy();
	}

}
