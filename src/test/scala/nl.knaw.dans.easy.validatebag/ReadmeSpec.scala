/*
 * Copyright (C) 2018 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.validatebag

import java.io.{ ByteArrayOutputStream, File }
import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReadmeSpec extends AnyFlatSpec with Matchers with CustomMatchers {
  private val clo = new CommandLineOptions(Array[String](), Configuration(Paths.get("src/main/assembly/dist"))) {
    // avoids System.exit() in case of invalid arguments or "--help"
    override def verify(): Unit = {}
  }

  private val helpInfo = {
    val mockedStdOut = new ByteArrayOutputStream()
    Console.withOut(mockedStdOut) {
      clo.printHelp()
    }
    mockedStdOut.toString
  }

  "options in help info" should "be part of index.md" in {
    val lineSeparators = s"(${ System.lineSeparator() })+"
    val options = helpInfo.split(s"${ lineSeparators }Options:$lineSeparators")(1)
    options.trim.length shouldNot be(0)
    new File("docs/index.md") should containTrimmed(options)
  }

  "synopsis in help info" should "be part of index.md" in {
    new File("docs/index.md") should containTrimmed(clo.synopsis)
  }

  "description line(s) in help info" should "be part of index.md and pom.xml" in {
    new File("docs/index.md") should containTrimmed(clo.description)
    new File("pom.xml") should containTrimmed(clo.description)
  }
}
