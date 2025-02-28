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
package nl.knaw.dans.easy.validatebag.rules

import better.files.File
import nl.knaw.dans.easy.validatebag.validation._
import nl.knaw.dans.easy.validatebag.{TargetBag, XmlValidator}
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import resource.managed

import java.net.{URI, URISyntaxException}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, Charset}
import java.nio.file.{Path, Paths}
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection._
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}
import scala.xml._

package object metadata extends DebugEnhancedLogging {
  private val filesXmlNamespace = "http://easy.dans.knaw.nl/schemas/bag/metadata/files/"
  private val dcxDaiNamespace = "http://easy.dans.knaw.nl/schemas/dcx/dai/"
  private val identifierTypeNamespace = "http://easy.dans.knaw.nl/schemas/vocab/identifier-type/"
  private val gmlNamespace = "http://www.opengis.net/gml"
  private val dcNamespace = "http://purl.org/dc/elements/1.1/"
  private val dctermsNamespace = "http://purl.org/dc/terms/"
  private val schemaInstanceNamespace = "http://www.w3.org/2001/XMLSchema-instance"

  private val allowedFilesXmlNamespaces = List(dcNamespace, dctermsNamespace)
  private val allowedAccessRights = List("ANONYMOUS", "RESTRICTED_REQUEST", "NONE")

  private val doiPattern: Regex = """^10(\.\d+)+/.+""".r
  private val doiUrlPattern: Regex = """^((https?://(dx\.)?)?doi\.org/(urn:)?(doi:)?)?10(\.\d+)+/.+""".r
  private val urnPattern: Regex = """^urn:[A-Za-z0-9][A-Za-z0-9-]{0,31}:[a-z0-9()+,\-\\.:=@;$_!*'%/?#]+$""".r

  private val daiPrefix = "info:eu-repo/dai/nl/"

  private val urlProtocols = List("http", "https")

  def xmlFileIfExistsConformsToSchema(xmlFile: Path, schemaName: String, validator: XmlValidator)
                                     (t: TargetBag): Try[Unit] = {
    trace(xmlFile)
    assume(!xmlFile.isAbsolute, "Path to xmlFile must be relative.")
    if ((t.bagDir / xmlFile.toString).exists) xmlFileConformsToSchema(xmlFile, schemaName, validator)(t)
    else Success(())
  }

  def xmlFileConformsToSchema(xmlFile: Path, schemaName: String, validator: XmlValidator)
                             (t: TargetBag): Try[Unit] = {
    trace(xmlFile)
    assume(!xmlFile.isAbsolute, "Path to xmlFile must be relative.")
    (t.bagDir / xmlFile.toString).inputStream.map(validator.validate).map {
      _.recoverWith { case t: Throwable => Try(reject(s"$xmlFile does not conform to $schemaName: ${t.getMessage}")) }
    }
  }.get()

  def filesXmlConformsToSchemaIfFilesNamespaceDeclared(validator: XmlValidator)
                                                      (t: TargetBag): Try[Unit] = {
    trace(())
    t.tryFilesXml.flatMap {
      xml =>
        if (xml.namespace == filesXmlNamespace) {
          logger.debug("Validating files.xml against XML Schema")
          xmlFileConformsToSchema(Paths.get("metadata/files.xml"), "files.xml", validator)(t)
        }
        else {
          logger.info(s"files.xml does not declare namespace $filesXmlNamespace, NOT validating with XML Schema")
          Success(())
        }
    }
  }

  def ddmMayContainDctermsLicenseFromList(allowedLicenses: Seq[URI])(t: TargetBag): Try[Unit] = {
    trace(())
    t.tryDdm.map {
      ddm =>
        val metadata = ddm \ "dcmiMetadata"
        val licenses = (metadata \ "license").toList
        debug(s"Found licences: ${licenses.mkString(", ")}")
        lazy val rightsHolders = (metadata \ "rightsHolder").toList
        licenses match {
          case license :: Nil if hasXsiType(dctermsNamespace, "URI")(license) =>
            (for {
              licenseUri <- getUri(license.text).recover { case _: URISyntaxException => reject("License must be a valid URI") }
              _ = if (licenseUri.getScheme != "http" && licenseUri.getScheme != "https") reject("License URI must have scheme http or https")
              normalizedLicenseUri <- normalizeLicenseUri(licenseUri)
              _ = if (!allowedLicenses.contains(normalizedLicenseUri)) reject(s"Found unknown or unsupported license: $licenseUri")
              _ = if (rightsHolders.isEmpty && !normalizedLicenseUri.toString.equals("http://creativecommons.org/publicdomain/zero/1.0")) reject(s"Valid license found, but no rightsHolder specified")
            } yield ()).get
          case Nil | _ :: Nil =>
            debug("No licences with xsi:type=\"dcterms:URI\"")
          case _ => reject(s"Found ${licenses.size} dcterms:license elements. Only one license is allowed.")
        }
    }
  }

  private def getUri(s: String): Try[URI] = Try {
    new URI(s)
  }

  def ddmContainsUrnNbnIdentifier(t: TargetBag): Try[Unit] = {
    trace(())
    for {
      ddm <- t.tryDdm
      urns <- getIdentifiers(ddm, "URN")
      nbns = urns.filter(_.contains("urn:nbn"))
      _ = if (nbns.isEmpty) reject("URN:NBN identifier is missing")
    } yield ()
  }

  def ddmDoiIdentifiersAreValid(t: TargetBag): Try[Unit] = {
    trace(())
    for {
      ddm <- t.tryDdm
      dois <- getIdentifiers(ddm, "DOI")
      _ <- doisAreSyntacticallyValid(dois)
    } yield ()
  }

  private def getIdentifiers(ddm: Node, idType: String): Try[Seq[String]] = Try {
    (ddm \\ "identifier")
      .filter(hasXsiType(identifierTypeNamespace, idType))
      .map(_.text)
  }

  private def doisAreSyntacticallyValid(dois: Seq[String]): Try[Unit] = Try {
    logger.debug(s"DOIs to check: ${dois.mkString(", ")}")
    val invalidDois = dois.filterNot(syntacticallyValidDoi)
    if (invalidDois.nonEmpty) reject(s"Invalid DOIs: ${invalidDois.mkString(", ")}")
  }

  private def syntacticallyValidDoi(doi: String): Boolean = {
    doiPattern.findFirstIn(doi).nonEmpty
  }

  private def syntacticallyValidDoiUrl(doi: String): Boolean = {
    doiUrlPattern.findFirstIn(doi).nonEmpty
  }

  private def syntacticallyValidUrn(urn: String): Boolean = {
    urnPattern.findFirstIn(urn).nonEmpty
  }

  /**
   * Converts all license URIs to one with scheme http and without any trailing slashes. Technically, these are not the same URIs but
   * for the purpose of identifying licenses this is good enough.
   *
   * @param uri the URI to normalize
   * @return normalized URI
   */
  def normalizeLicenseUri(uri: URI): Try[URI] = Try {
    def normalizeLicenseUriPath(p: String): String = {
      val nTrailingSlashes = p.toCharArray.reverse.takeWhile(_ == '/').length
      p.substring(0, p.length - nTrailingSlashes)
    }

    def normalizeLicenseUriScheme(s: String): String = {
      if (s == "http" || s == "https") "http"
      else throw new IllegalArgumentException(s"Only http or https license URIs allowed. URI scheme found: $s")
    }

    new URI(normalizeLicenseUriScheme(uri.getScheme), uri.getUserInfo, uri.getHost, uri.getPort, normalizeLicenseUriPath(uri.getPath), uri.getQuery, uri.getFragment)
  }

  private def hasXsiType(attrNamespace: String, attrValue: String)(e: Node): Boolean = {
    e.attribute(schemaInstanceNamespace, "type")
      .exists {
        case Seq(n) =>
          n.text.split(":") match {
            case Array(pref, label) => e.getNamespace(pref) == attrNamespace && label == attrValue
            case _ => false
          }
        case _ => false
      }
  }

  def ddmMustHaveRightsHolder(t: TargetBag): Try[Unit] = {
    trace(())
    for {
      ddm <- t.tryDdm
      inRole = (ddm \\ "role").text.toLowerCase.contains("rightsholder") // FIXME: this will true if there is a <role>rightsholder</role> anywhere in the document
      _ = if (!inRole && (ddm \ "dcmiMetadata" \ "rightsHolder").isEmpty)
        reject(s"No rightsHolder")
    } yield ()
  }

  def ddmDaisAreValid(t: TargetBag): Try[Unit] = {
    trace(())
    for {
      ddm <- t.tryDdm
      _ <- daisAreValid(ddm)
    } yield ()
  }

  private def daisAreValid(ddm: Node): Try[Unit] = Try {
    val dais = (ddm \\ "DAI").filter(_.namespace == dcxDaiNamespace)
    logger.debug(s"DAIs to check: ${dais.mkString(", ")}")
    val invalidDais = dais.map(_.text.stripPrefix(daiPrefix)).filterNot(s => digest(s.slice(0, s.length - 1), 9) == s.last)
    if (invalidDais.nonEmpty) reject(s"Invalid DAIs: ${invalidDais.mkString(", ")}")
  }

  // Calculated the check digit of a DAI. Implementation copied from easy-ddm.
  private def digest(message: String, modeMax: Int): Char = {
    val reverse = message.reverse
    var f = 2
    var w = 0
    var mod = 0
    mod = 0
    while ( {
      mod < reverse.length
    }) {
      val cx = reverse.charAt(mod)
      val x = cx - 48
      w += f * x
      f += 1
      if (f > modeMax) f = 2

      {
        mod += 1;
        mod
      }
    }
    mod = w % 11
    if (mod == 0) '0'
    else {
      val c = 11 - mod
      if (c == 10) 'X'
      else (c + 48).toChar
    }
  }

  def ddmGmlPolygonPosListIsWellFormed(t: TargetBag): Try[Unit] = {
    trace(())
    for {
      ddm <- t.tryDdm
      posLists <- getPolygonPosLists(ddm)
      _ <- posLists.map(validatePosList).collectResults.recoverWith {
        case ce: CompositeException => Try(reject(ce.getMessage))
      }
    } yield ()
  }

  private def getPolygonPosLists(parent: Node): Try[Seq[Node]] = Try {
    trace(())
    val polygons = getPolygons(parent)
    polygons.flatMap(_ \\ "posList")
  }

  private def getPolygons(parent: Node): NodeSeq = {
    (parent \\ "Polygon").filter(_.namespace == gmlNamespace)
  }

  private def validatePosList(node: Node): Try[Unit] = Try {
    trace(node)

    def offendingPosListMsg(values: Seq[String]): String = {
      s"(Offending posList starts with: ${values.take(10).mkString(", ")}...)"
    }

    val values = node.text.split("""\s+""").toList
    val numberOfValues = values.size
    if (numberOfValues % 2 != 0) reject(s"Found posList with odd number of values: $numberOfValues. ${offendingPosListMsg(values)}")
    if (numberOfValues < 8) reject(s"Found posList with too few values (fewer than 4 pairs). ${offendingPosListMsg(values)}")
    if (values.take(2) != values.takeRight(2)) reject(s"Found posList with unequal first and last pairs. ${offendingPosListMsg(values)}")
  }

  def polygonsInSameMultiSurfaceHaveSameSrsName(t: TargetBag): Try[Unit] = {
    trace(())
    val result = for {
      ddm <- t.tryDdm
      multiSurfaces <- getMultiSurfaces(ddm)
      _ <- multiSurfaces.map(validateMultiSurface).collectResults
    } yield ()

    result.recoverWith {
      case ce: CompositeException => Try(reject(ce.getMessage))
    }
  }

  private def getMultiSurfaces(ddm: Node): Try[NodeSeq] = Try {
    (ddm \\ "MultiSurface").filter(_.namespace == gmlNamespace)
  }

  private def validateMultiSurface(ms: Node): Try[Unit] = {
    val polygons = getPolygons(ms)
    if (polygons.isEmpty || polygons.flatMap(_.attribute("srsName").map(_.text)).distinct.size <= 1) Success(())
    else Try(reject("Found MultiSurface element containing polygons with different srsNames"))
  }

  def pointsHaveAtLeastTwoValues(t: TargetBag): Try[Unit] = {
    trace(())
    val result = for {
      ddm <- t.tryDdm
      rdToSpatials <- Try((ddm \\ "spatial").groupBy(isRdPoint))
      rdToPoints <- Try(rdToSpatials.map { case (isRD, spatial) => isRD -> spatial.theSeq.flatMap(getGmlPoints) })
      _ <- rdToPoints.toSeq.flatMap { case (isRD, points) =>
        points.map(validatePoint(_, isRD))
      }.collectResults
    } yield ()

    result.recoverWith {
      case ce: CompositeException => Try(reject(ce.getMessage))
    }
  }

  private def getGmlPoints(ddm: Node): Seq[Node] = {
    ((ddm \\ "Point") ++ (ddm \\ "lowerCorner") ++ (ddm \\ "upperCorner")).filter(_.namespace == gmlNamespace)
  }.theSeq

  private def validatePoint(point: Node, isRD: Boolean): Try[Unit] = Try {
    val value = point.text.trim
    val coordinates = Try {
      value.split("""\s+""").map(_.trim.toFloat)
    }
      .getOrRecover(_ => reject(s"Point has non numeric coordinates: $value"))
    if (coordinates.length < 2) reject(s"Point has less than two coordinates: $value")
    else if (isRD && !isValidaRdRange(coordinates))
      reject(s"Point is outside RD bounds: $value")
  }

  def isRdPoint(spatial: Node): Boolean = {
    spatial.attribute("srsName").toSeq.flatten
      .exists(_.text == "http://www.opengis.net/def/crs/EPSG/0/28992")
  }

  def isValidaRdRange(coordinates: Seq[Float]): Boolean = {
    val x = coordinates.head
    val y = coordinates.tail.head
    x >= -7000 && x <= 300000 && y >= 289000 && y <= 629000
  }

  def archisIdentifiersHaveAtMost10Characters(t: TargetBag): Try[Unit] = {
    trace(())
    for {
      ddm <- t.tryDdm
      identifiers = getArchisIdentifiers(ddm)
      validationErrors = identifiers.map(validateArchisIdentifier).collect { case Failure(e) => e.getMessage }
      _ = if (validationErrors.nonEmpty) reject(formatInvalidArchisIdentifiers(validationErrors).mkString("\n"))
    } yield ()
  }

  private def getArchisIdentifiers(ddm: Node): Seq[String] = {
    (ddm \\ "identifier")
      .withFilter(_.attributes
        .filter(_.prefixedKey == "xsi:type")
        .filter(_.value.text == "id-type:ARCHIS-ZAAK-IDENTIFICATIE")
        .nonEmpty)
      .map(_.text)
  }

  private def validateArchisIdentifier(identifier: String): Try[Unit] = {
    if (identifier.length <= 10) Success(())
    else Try(reject(s"Archis identifier must be 10 or fewer characters long: $identifier"))
  }

  private def formatInvalidArchisIdentifiers(results: Seq[String]): Seq[String] = {
    results.zipWithIndex.map { case (msg, index) => s"(${index + 1}) $msg" }
  }

  def allUrlsAreValid(t: TargetBag): Try[Unit] = {
    trace(())
    val result = for {
      ddm <- t.tryDdm
      urlAttributeValues <- getUrlTypeAttributeValues(ddm)
      urlElementValues <- getUrlTypeElementValues(ddm)
      doiElementValue <- Try(DoiKey -> getTypeElementValues(ddm, "DOI"))
      urnElementValue <- Try(UrnKey -> getTypeElementValues(ddm, "URN"))
      _ <- validateUrls(Seq(doiElementValue, urnElementValue) ++ urlAttributeValues ++ urlElementValues)
    } yield ()

    result.recoverWith {
      case ce: CompositeException => Try(reject(ce.getMessage))
    }
  }

  private sealed abstract class UrlValidationKey

  private case class UrlAttributeKey(name: String) extends UrlValidationKey

  private case object UrlKey extends UrlValidationKey

  private case object DoiKey extends UrlValidationKey

  private case object UrnKey extends UrlValidationKey

  private def getUrlTypeAttributeValues(ddm: Node): Try[Seq[(UrlValidationKey, Seq[String])]] = Try {
    (UrlAttributeKey("href") -> getAttributeValues(ddm, "href")) ::
      List("schemeURI", "valueURI")
        .map(attribute => UrlAttributeKey(attribute) -> getAttributeValues(ddm \\ "subject", attribute))
  }

  private def getAttributeValues(nodes: NodeSeq, attribute: String): Seq[String] = {
    (nodes \\ s"@$attribute")
      .map(_.text)
  }

  private def getUrlTypeElementValues(ddm: Node): Try[Seq[(UrlValidationKey, Seq[String])]] = Try {
    List("xsi:type", "scheme")
      .map(attribute => UrlKey -> getElementValues(ddm, attribute, List("dcterms:URI", "dcterms:URL", "URI", "URL")))
  }

  private def getTypeElementValues(ddm: Node, idType: String) = {
    getElementValues(ddm, "scheme", List(idType, s"id-type:$idType"), List("href"))
  }

  /**
   * Returns the bodies of all leaf nodes in ''node'', for which the given ''attribute'' has one of the given ''attributeValues'',
   * except if that leaf node also contains an attribute in ''excludeOnAttribute''.
   *
   * @param node               the `Node` for which to find all bodies in its leafs
   * @param attribute          only include bodies of leaf nodes containing this attribute
   * @param attributeValues    only include bodies of leaf nodes when an attribute contains one of these values
   * @param excludeOnAttribute only include bodies of leaf nodes that do NOT contain one of these attributes
   * @return a list of bodies of leaf nodes, filtered by the given attribute filters.
   */
  private def getElementValues(node: Node, attribute: String, attributeValues: List[String], excludeOnAttribute: List[String] = Nil): Seq[String] = {
    (node \\ "_")
      .withFilter(_.attributes
        .filter(_.prefixedKey == attribute)
        .filter(attributeValues contains _.value.text)
        .nonEmpty)
      .withFilter(_.attributes
        .filter(md => excludeOnAttribute.contains(md.prefixedKey))
        .isEmpty)
      .map(_.text)
  }

  private def validateUrls(urls: Seq[(UrlValidationKey, Seq[String])]): Try[Unit] = {
    urls
      .flatMap { case (key, urls) => urls.map(validateUrl(key)) }
      .collectResults
      .map(_ => ())
  }

  private def validateUrl(key: UrlValidationKey)(url: String): Try[Unit] = {
    key match {
      case UrlAttributeKey(name) => validateUrlType(url, Some(name))
      case UrlKey => validateUrlType(url)
      case DoiKey => validateDoiType(url)
      case UrnKey => validateUrnType(url)
    }
  }

  private def validateUrlType(url: String, name: Option[String] = None): Try[Unit] = {
    val msg = name.fold("")(name => s" (value of attribute '$name')")
    for {
      uri <- getUri(url).recover { case _: URISyntaxException => reject(s"$url is not a valid URI") }
      scheme = uri.getScheme
      _ = if (!(urlProtocols contains scheme))
        reject(s"protocol '$scheme' in URI '$url' is not one of the accepted protocols [${urlProtocols.mkString(",")}]$msg")
      else ()
    } yield ()
  }

  private def validateDoiType(doi: String): Try[Unit] = Try {
    if (syntacticallyValidDoiUrl(doi)) ()
    else reject(s"DOI '$doi' is not valid")
  }

  private def validateUrnType(urn: String): Try[Unit] = Try {
    if (syntacticallyValidUrn(urn)) ()
    else reject(s"URN '$urn' is not valid")
  }

  def filesXmlHasDocumentElementFiles(t: TargetBag): Try[Unit] = {
    trace(())
    t.tryFilesXml.map(
      xml => if (xml.label != "files") reject("files.xml: document element must be 'files'"))
  }

  def filesXmlHasOnlyFiles(t: TargetBag): Try[Unit] = {
    trace(())
    t.tryFilesXml.map {
      files =>
        if (files.namespace == filesXmlNamespace) {
          debug("Rule filesXmlHasOnlyFiles has been checked by files.xsd")
        }
        else {
          val nonFiles = (files \ "_").filterNot(_.label == "file")
          if (nonFiles.nonEmpty) reject(s"files.xml: children of document element must only be 'file'. Found non-file elements: ${nonFiles.mkString(", ")}")
        }
    }
  }

  def filesXmlFileElementsAllHaveFilepathAttribute(t: TargetBag): Try[Unit] = {
    trace(())
    t.tryFilesXml.map {
      xml =>
        val elemsWithoutFilePath = (xml \ "file").filter(_.attribute("filepath").isEmpty)
        if (elemsWithoutFilePath.nonEmpty) reject(s"${elemsWithoutFilePath.size} 'file' element(s) don't have a 'filepath' attribute")
    }
  }

  def filesXmlFileElementsInOriginalFilePaths(t: TargetBag): Try[Unit] = {
    trace(())

    t.tryOptOriginal2PhysicalFilePath match {
      case Failure(e) if e.getClass.getSimpleName == "NoSuchFileException" => Success(())
      case Failure(e) => Failure(e) // not expected to happen
      case Success(None) => Success(())
      case Success(Some(original2PhysicalFilePath)) =>
        val originalFilePaths = original2PhysicalFilePath.keySet
        t.tryFilesXml.map { xml =>
          val notInOriginalPaths = (xml \ "file")
            .map(_.attribute("filepath").getOrElse(Seq.empty).headOption.map(_.text))
            .withFilter(_.isDefined)
            .map(_.get)
            .toSet
            .diff(originalFilePaths)
          if (notInOriginalPaths.nonEmpty)
            reject(s"${notInOriginalPaths.size} 'filepath' attributes are not found in 'original-filepaths.txt' ${notInOriginalPaths.mkString(", ")}. ")
        }
    }
  }

  def filesXmlNoDuplicatesAndMatchesWithPayloadPlusPreStagedFiles(t: TargetBag): Try[Unit] = {
    trace(())
    if (t.hasOriginalFilePathsFile) Success(())
    else
      t.tryFilesXml.map { xml =>
        val files = xml \ "file"
        val pathsInFilesXmlList = files.map(_ \@ "filepath")
        val duplicatePathsInFilesXml = pathsInFilesXmlList.groupBy(identity).collect { case (k, vs) if vs.size > 1 => k }
        val noDuplicatesFound = duplicatePathsInFilesXml.isEmpty
        val pathsInFileXml = pathsInFilesXmlList.toSet
        val filesInBagPayload = (t.bagDir / "data").walk().filter(_.isRegularFile).toSet
        val payloadPaths = filesInBagPayload.map(t.bagDir.path relativize _).map(_.toString)
        val payloadAndPreStagedFilePaths = payloadPaths ++ t.preStagedFilePaths
        val fileSetsEqual = pathsInFileXml == payloadAndPreStagedFilePaths

        if (noDuplicatesFound && fileSetsEqual) ()
        else {
          def stringDiff[T](name: String, left: Set[T], right: Set[T]): String = {
            val set = left diff right
            if (set.isEmpty) ""
            else s"only in $name: " + set.mkString("{", ", ", "}")
          }

          lazy val onlyInBag = stringDiff("bag", payloadPaths, pathsInFileXml)
          lazy val onlyInPreStaged = stringDiff("pre-staged.csv", t.preStagedFilePaths, pathsInFileXml)
          lazy val onlyInFilesXml = stringDiff("files.xml", pathsInFileXml, payloadAndPreStagedFilePaths)

          val msg1 = if (noDuplicatesFound) ""
          else s"   - Duplicate filepaths found: ${duplicatePathsInFilesXml.mkString("{", ", ", "}")}\n"
          val msg2 = if (fileSetsEqual) ""
          else "   - Filepaths in files.xml not equal to files found in data folder. Difference - " +
            s"$onlyInBag $onlyInPreStaged $onlyInFilesXml"

          val msg = msg1 + msg2
          reject(s"files.xml: errors in filepath-attributes:\n$msg")
        }
      }
  }

  def filesXmlAllFilesHaveFormat(t: TargetBag): Try[Unit] = {
    trace(())
    t.tryFilesXml.map { xml =>
      val files = xml \ "file"
      val allFilesHaveFormat = files.forall(_.child.exists(n =>
        xml.getNamespace(n.prefix) == "http://purl.org/dc/terms/" && n.label == "format"))
      if (!allFilesHaveFormat) reject("files.xml: not all <file> elements contain a <dcterms:format>")
    }
  }

  def filesXmlFilesHaveOnlyAllowedNamespaces(t: TargetBag): Try[Unit] = {
    trace(())
    t.tryFilesXml.map { xml =>
      if (xml.namespace == filesXmlNamespace) {
        debug("Rule filesXmlFilesHaveOnlyAllowedNamespaces has been checked by files.xsd")
      }
      else {
        val fileChildren = xml \ "file" \ "_"
        val hasOnlyAllowedNamespaces = fileChildren.forall {
          case n: Elem => allowedFilesXmlNamespaces contains xml.getNamespace(n.prefix)
          case _ => true // Don't check non-element nodes
        }
        if (!hasOnlyAllowedNamespaces) reject("files.xml: non-dc/dcterms elements found in some file elements")
      }
    }
  }

  def filesXmlFilesHaveOnlyAllowedAccessRights(t: TargetBag): Try[Unit] = {
    trace(())
    for {
      xml <- t.tryFilesXml
      _ <- validateFileAccessRights(xml \ "file")
    } yield ()
  }

  private def validateFileAccessRights(files: NodeSeq): Try[Unit] = {
    files.map(validateAccessRights).collectResults.map(_ => ()).recoverWith {
      case ce: CompositeException => Try(reject(ce.getMessage))
    }
  }

  private def validateAccessRights(file: Node): Try[Unit] = {
    val accessRights = file \ "accessRights"
    accessRights.map(rights =>
      if (!allowedAccessRights.contains(rights.text))
        Try {
          reject(s"files.xml: invalid access rights '${rights.text}' in accessRights element for file: '${file \@ "filepath"}' (allowed values ${allowedAccessRights.mkString(", ")})")
        }
      else Success(())
    )
      .collectResults
      .map(_ => ())
  }

  def optionalFileIsUtf8Decodable(f: Path)(t: TargetBag): Try[Unit] = {
    for {
      _ <- Try(assume(!f.isAbsolute, "Path to UTF-8 text file must be relative."))
      file = t.bagDir / f.toString
      _ <- if (file.exists) isValidUtf8(file.byteArray).recoverWith { case e: CharacterCodingException => reject(s"Input not valid UTF-8: ${e.getMessage}") }
      else Success(())
    } yield ()
  }

  private def isValidUtf8(input: Array[Byte]): Try[Unit] = {
    val cs = Charset.forName("UTF-8").newDecoder
    Try {
      cs.decode(ByteBuffer.wrap(input))
    }
  }
}
