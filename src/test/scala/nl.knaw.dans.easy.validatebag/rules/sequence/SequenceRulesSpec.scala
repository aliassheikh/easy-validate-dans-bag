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
package nl.knaw.dans.easy.validatebag.rules.sequence

import java.io.IOException
import java.net.URI
import java.util.UUID

import nl.knaw.dans.easy.validatebag.{ BagStore, TestSupportFixture }
import org.scalamock.scalatest.MockFactory

import scala.util.{ Failure, Success }

class SequenceRulesSpec extends TestSupportFixture with MockFactory {
  private val bagStoreMock = mock[BagStore]
  private val isVersionOfBagInfo_1 =
    """Payload-Oxum: 0.1
      |Bagging-Date: 2018-09-03
      |Bag-Size: 0.4 KB
      |Created: 2015-05-19T00:00:00.000+02:00
      |EASY-User-Account: user001""".stripMargin
  private val isVersionOfBagInfo_2 =
    """Payload-Oxum: 0.1
      |Bagging-Date: 2018-09-03
      |Bag-Size: 0.4 KB
      |Created: 2015-05-19T00:00:00.000+02:00
      |EASY-User-Account: user002""".stripMargin

  private def expectUuidDoesNotExist(): Unit = {
    (bagStoreMock.bagExists(_: UUID)) expects * anyNumberOfTimes() returning Success(false)
  }

  private def expectUuidExists(): Unit = {
    (bagStoreMock.bagExists(_: UUID)) expects * anyNumberOfTimes() returning Success(true)
  }

  private def expectUuidDoesNotExistInThisStore(): Unit = {
    (bagStoreMock.getBagStoreUrl _) expects() twice() returning (new URI("https://host:99999/stores/wrongstore"))
    (bagStoreMock.bagExistsInThisStore(_: UUID)) expects * anyNumberOfTimes() returning Success(false)
  }

  private def expectUuidExistsInThisStore(): Unit = {
    (bagStoreMock.getBagStoreUrl _) expects() once() returning (null)
    (bagStoreMock.bagExistsInThisStore(_: UUID)) expects * never()
  }

  private def expectDifferentUser(): Unit = {
    (bagStoreMock.getBagInfoText(_: UUID)) expects * once() returning Success(isVersionOfBagInfo_2)
  }

  private def expectSameUser(): Unit = {
    (bagStoreMock.getBagInfoText(_: UUID)) expects * once() returning Success(isVersionOfBagInfo_1)
  }

  private def expectBagStoreIoException(): Unit = {
    (bagStoreMock.bagExists(_: UUID)) expects * anyNumberOfTimes() returning Failure(new IOException())
  }

  "bagInfoIsVersionOfIfExistsPointsToArchivedBag" should "cause rejection if UUID not found in bag-store" in {
    expectUuidDoesNotExist()
    testRuleViolation(rule = bagInfoIsVersionOfIfExistsPointsToArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of", includedInErrorMsg = "not found in bag stores", doubleCheckBagItValidity = false)
  }

  it should "fail if bag store was not on-line" in {
    expectBagStoreIoException()
    testRuleFailure(rule = bagInfoIsVersionOfIfExistsPointsToArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of", includedInErrorMsg = "because of an I/O error", doubleCheckBagItValidity = false)
  }

  it should "succeed if bag was found" in {
    expectUuidExists()
    testRuleSuccess(rule = bagInfoIsVersionOfIfExistsPointsToArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of", doubleCheckBagItValidity = false)
  }

  it should "succeed if no Is-Version-Of field is present" in {
    expectUuidDoesNotExist()
    testRuleSuccess(rule = bagInfoIsVersionOfIfExistsPointsToArchivedBag(bagStoreMock), inputBag = "baginfo-without-is-version-of", doubleCheckBagItValidity = false)
  }

  it should "cause rejection if a Is-Version-Of field is present without urn" in {
    expectUuidDoesNotExist()
    testRuleViolation(rule = bagInfoIsVersionOfIfExistsPointsToArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of-without-urn", includedInErrorMsg = "Is-Version-Of value must be a URN", doubleCheckBagItValidity = false)
  }

  it should "cause rejection if the scheme part does not start with uuid" in {
    expectUuidDoesNotExist()
    testRuleViolation(rule = bagInfoIsVersionOfIfExistsPointsToArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of-invalid-scheme", includedInErrorMsg = "Is-Version-Of URN must be of subtype UUID", doubleCheckBagItValidity = false)
  }

  it should "cause rejection if the UUID is NOT in canonical textual representation" in {
    expectUuidDoesNotExist()
    testRuleViolation(rule = bagInfoIsVersionOfIfExistsPointsToArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of-invalid-uuid", includedInErrorMsg = "String '75fc6989/hierook-4c7a-b49d-superinvalidenzo' is not a UUID", doubleCheckBagItValidity = false)
  }

  "storeSameAsInArchivedBag" should "cause rejection if UUID not found in this bag-store" in {
    expectUuidDoesNotExistInThisStore()
    testRuleViolation(rule = storeSameAsInArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of", includedInErrorMsg = "not found in bag store https://host:99999/stores/wrongstore", doubleCheckBagItValidity = false)
  }

  it should "succeed if bag was found in this bag-store" in {
    expectUuidExistsInThisStore()
    testRuleSuccess(rule = storeSameAsInArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of", doubleCheckBagItValidity = false)
  }

  "userSameAsInArchivedBag" should "cause rejection if the user is different in Is-Version-Of bag" in {
    expectDifferentUser()
    testRuleViolation(rule = userSameAsInArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of", includedInErrorMsg = "User user001 is different from the user user002", doubleCheckBagItValidity = false)
  }

  it should "succeed when the user is the same in Is-Version-Of bag" in {
    expectSameUser()
    testRuleSuccess(rule = userSameAsInArchivedBag(bagStoreMock), inputBag = "baginfo-with-is-version-of", doubleCheckBagItValidity = false)
  }
}
