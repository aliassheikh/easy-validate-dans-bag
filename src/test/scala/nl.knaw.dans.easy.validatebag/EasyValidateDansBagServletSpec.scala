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

import java.net.URI

import nl.knaw.dans.easy.validatebag.InfoPackageType.{ AIP, SIP }
import nl.knaw.dans.lib.encode.StringEncoding
import org.apache.commons.configuration.PropertiesConfiguration
import org.eclipse.jetty.http.HttpStatus.{ BAD_REQUEST_400, OK_200 }
import org.scalatest.OptionValues._
import org.scalatra.test.EmbeddedJettyContainer
import org.scalatra.test.scalatest.ScalatraSuite

import scala.util.Try

class EasyValidateDansBagServletSpec extends TestSupportFixture
  with SchemaFixture
  with EmbeddedJettyContainer
  with ScalatraSuite {
  private val testVersion = "1.0.0"

  private val schemasAreAvailable =
    if (isAvailable(triedAgreementSchema, triedFileSchema, triedDdmSchema)) {
      // offline schema's -> no app -> no servlet
      // (otherwise test suite aborts, now tests are ignored)
      val configuration = Configuration(
        testVersion,
        createProperties(),
        Seq(new URI("http://creativecommons.org/licenses/by-sa/4.0")),
      )
      val app = new EasyValidateDansBagApp(configuration)
      val servlet = new EasyValidateDansBagServlet(app)
      addServlet(servlet, "/*")
      true
    }
    else false

  "the validate handler" should "return a 200 and the response when presented a valid bag uri" in {
    assume(schemasAreAvailable)
    post(uri = createValidateURL("SIP", "valid-bag"), headers = Seq(("Accept", "application/json"))) {
      status shouldBe OK_200
      val resultMessage = ResultMessage.read(body)
      resultMessage.infoPackageType shouldBe SIP
      resultMessage.ruleViolations shouldBe empty
      resultMessage.isCompliant shouldBe true
    }
  }

  it should "return a 200 and a response including 'compliant: false' and reasons when presented an invalid SIP bag" in {
    assume(schemasAreAvailable)
    post(uri = createValidateURL("SIP", "metadata-correct"), headers = Seq(("Accept", "application/json"))) {
      status shouldBe OK_200
      val resultMessage = ResultMessage.read(body)
      resultMessage.bagUri shouldBe new URI(s"file://${ bagsDir.path.toAbsolutePath }/metadata-correct/")
      resultMessage.bag shouldBe "metadata-correct"
      resultMessage.profileVersion shouldBe 0
      resultMessage.infoPackageType shouldBe SIP
      resultMessage.isCompliant shouldBe false
      resultMessage.ruleViolations.value.toList should contain only (
        ("1.2.4(a)", "bag-info.txt must contain exactly one 'Created' element; number found: 0")
        )
    }
  }

  it should "return a 200 and a response including 'compliant: false' and reasons when presented an invalid AIP bag" in {
    assume(schemasAreAvailable)
    post(uri = createValidateURL("AIP", "metadata-correct"), headers = Seq(("Accept", "application/json"))) {
      status shouldBe OK_200
      val resultMessage = ResultMessage.read(body)
      resultMessage.bagUri shouldBe new URI(s"file://${ bagsDir.path.toAbsolutePath }/metadata-correct/")
      resultMessage.bag shouldBe "metadata-correct"
      resultMessage.profileVersion shouldBe 0
      resultMessage.infoPackageType shouldBe AIP
      resultMessage.isCompliant shouldBe false
      resultMessage.ruleViolations.value.toList should contain inOrderOnly(
        ("1.2.4(a)", "bag-info.txt must contain exactly one 'Created' element; number found: 0"),
        ("1.2.6(a)", "bag-info.txt must contain exactly one 'EASY-User-Account' element; number found: 0"),
        ("1.3.1(a)", "Mandatory file 'manifest-sha1.txt' not found in bag."),
        ("3.1.3(a)", "URN:NBN identifier is missing"),
      )
    }
  }

  it should "return a 200 and a response including 'compliant: false' and reasons when files are in the wrong dir" in {
    assume(schemasAreAvailable)
    post(uri = createValidateURL("SIP", "files-in-wrong-metadata-dir"), headers = Seq(("Accept", "application/json"))) {
      status shouldBe OK_200
      val resultMessage = ResultMessage.read(body)
      resultMessage.bagUri shouldBe new URI(s"file://${ bagsDir.path.toAbsolutePath }/files-in-wrong-metadata-dir/")
      resultMessage.ruleViolations.value should contain("2.5", "Directory metadata contains files or directories that are not allowed: metadata/agreements.xml, metadata/message-from-depositor.txt")
    }
  }

  it should "return a 400 if presented a non existing bag uri" in {
    assume(schemasAreAvailable)
    post(uri = createValidateURL("SIP", "_._metadata-correct"), headers = Seq(("Accept", "application/json"))) {
      status shouldBe BAD_REQUEST_400
      body should include("Bag does not exist")
    }
  }

  it should "return a 400 if the infoPackageType is not valid" in {
    assume(schemasAreAvailable)
    post(uri = createValidateURL("sip", "valid-bag"), headers = Seq(("Accept", "application/json"))) {
      status shouldBe BAD_REQUEST_400
      body shouldBe "Input error: invalid InfoPackageType 'sip'"
    }
  }

  it should "return a 200 for a bag with spaces in the name and mark it as 'compliant'" in {
    assume(schemasAreAvailable)
    post(uri = createValidateURL("SIP", "bag with spaces"), headers = Seq(("Accept", "application/json"))) {
      status shouldBe OK_200
      val resultMessage = ResultMessage.read(body)
      resultMessage.infoPackageType shouldBe SIP
      resultMessage.isCompliant shouldBe true
    }
  }

  "get /" should "return the service is running and the current version" in {
    assume(schemasAreAvailable)
    get("/") {
      status shouldBe OK_200
      body shouldBe s"EASY Validate DANS Bag Service running v$testVersion."
    }
  }

  private def createValidateURL(infoPackageTypeString: String, bagName: String) = {
    val encodedURI = (bagsDir / bagName).uri.toString.escapeString
    s"/validate?infoPackageType=$infoPackageTypeString&uri=$encodedURI"
  }

  private def createProperties(): PropertiesConfiguration = {
    val properties = new PropertiesConfiguration()
    properties.setProperty("schemas.ddm", ddmSchemaUrl)
    properties.setProperty("schemas.files", filesSchemaUrl)
    properties.setProperty("schemas.agreements", agreementsSchemaUrl)
    properties.setProperty("schemas.provenance", provenanceSchemaUrl)
    properties.setProperty("schemas.amd", amdSchemaUrl)
    properties.setProperty("schemas.emd", emdSchemaUrl)
    properties.setProperty("bagstore-service.base-url", bagsDir.path.toAbsolutePath.toString)
    properties
  }
}
