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

package org.springframework.boot.build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.util.FileCopyUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ConventionsPlugin}.
 *
 * @author Christoph Dreis
 */
class ConventionsPluginTests {

	private File projectDir;

	private File buildFile;

	@BeforeEach
	void setup(@TempDir File projectDir) throws IOException {
		this.projectDir = projectDir;
		this.buildFile = new File(this.projectDir, "build.gradle");
	}

	@Test
	void jarIncludesLegalFiles() throws IOException {
		try (PrintWriter out = new PrintWriter(new FileWriter(this.buildFile))) {
			out.println("plugins {");
			out.println("    id 'java'");
			out.println("    id 'org.springframework.boot.conventions'");
			out.println("}");
			out.println("description 'Test'");
			out.println("jar.archiveFileName = 'test.jar'");
		}
		runGradle("jar");
		File file = new File(this.projectDir, "/build/libs/test.jar");
		assertThat(file).exists();
		try (JarFile jar = new JarFile(file)) {
			JarEntry license = jar.getJarEntry("META-INF/LICENSE.txt");
			assertThat(license).isNotNull();
			JarEntry notice = jar.getJarEntry("META-INF/NOTICE.txt");
			assertThat(notice).isNotNull();
			String noticeContent = FileCopyUtils.copyToString(new InputStreamReader(jar.getInputStream(notice)));
			// Test that variables were replaced
			assertThat(noticeContent).doesNotContain("${");
		}
	}

	private void runGradle(String... args) {
		GradleRunner.create().withProjectDir(this.projectDir).withArguments(args).withPluginClasspath().build();
	}

}
