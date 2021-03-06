/*
 * Copyright (c) 2012-2013 SnowPlow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.hadoop
package enrichments

// Scala
import scala.collection.mutable.ListBuffer

// Scalaz
import scalaz._
import Scalaz._

// SnowPlow Utils
import com.snowplowanalytics.util.Tap._

// Scala MaxMind GeoIP
import com.snowplowanalytics.maxmind.geoip.IpGeo

// This project
import inputs.{CanonicalInput, NVGetPayload}
import outputs.CanonicalOutput

import utils.{ConversionUtils => CU}
import utils.MapTransformer._

import enrichments.{EventEnrichments => EE}
import enrichments.{MiscEnrichments => ME}
import enrichments.{ClientEnrichments => CE}
import enrichments.{GeoEnrichments => GE}
import web.{PageEnrichments => PE}
import web.{AttributionEnrichments => AE}

/**
 * A module to hold our enrichment process.
 *
 * At the moment this is very fixed - no
 * support for configuring enrichments etc.
 */
object EnrichmentManager {

  /**
   * Runs our enrichment process.
   *
   * @param input Our canonical input
   *        to enrich
   * @return a MaybeCanonicalOutput - i.e.
   *         a ValidationNel containing
   *         either failure Strings or a
   *         NonHiveOutput.
   */
  def enrichEvent(geo: IpGeo, raw: CanonicalInput): ValidatedCanonicalOutput = {

    // Placeholders for where the Success value doesn't matter.
    // Useful when you're updating large (>22 field) POSOs.
    val unitSuccess = ().success[String]
    val unitSuccessNel = ().successNel[String]

    // Retrieve the payload
    // TODO: add support for other
    // payload types in the future
    val parameters = raw.payload match {
      case NVGetPayload(p) => p
      case _ => throw new FatalEtlError("Only name-value pair GET payloads are currently supported") // TODO: change back to FatalEtlException when Cascading FailureTrap supports exclusions
    }

    // 1. Enrichments not expected to fail

    // Let's start populating the CanonicalOutput
    // with the fields which cannot error
    val event = new CanonicalOutput().tap { e =>
      e.collector_tstamp = EE.toTimestamp(raw.timestamp)
      e.event_id = EE.generateEventId
      e.event_vendor = "com.snowplowanalytics" // TODO: this should be moved to Tracker Protocol
      e.v_collector = raw.source.collector // May be updated later if we have a `cv` parameter
      e.v_etl = ME.etlVersion
      raw.ipAddress.map(ip => e.user_ipaddress = ip)
    }

    // 2. Enrichments which can fail

    // 2a. Failable enrichments which don't need the payload

    // Attempt to decode the useragent
    val useragent = raw.userAgent match {
      case Some(ua) =>
        val u = CU.decodeString(raw.encoding, "useragent", ua)
        u.flatMap(ua => {
          event.useragent = ua
          ua.success
          })
      case None => unitSuccess // No fields updated
    }

    // Parse the useragent
    val client = raw.userAgent match {
      case Some(ua) =>
        val ca = CE.extractClientAttributes(ua)
        ca.flatMap(c => {
          event.br_name = c.browserName
          event.br_family = c.browserFamily
          c.browserVersion.map(bv => event.br_version = bv)
          event.br_type = c.browserType
          event.br_renderengine = c.browserRenderEngine
          event.os_name = c.osName
          event.os_family = c.osFamily
          event.os_manufacturer = c.osManufacturer
          event.dvce_type = c.deviceType
          event.dvce_ismobile = CU.booleanToJByte(c.deviceIsMobile)
          c.success
          })
        ca
      case None => unitSuccess // No fields updated
    }

    // 2b. Failable enrichments using the payload

    // Partially apply decodeString to create a TransformFunc
    val decodeString: TransformFunc = CU.decodeString(raw.encoding, _, _)

    // We use a TransformMap which takes the format:
    // "source key" -> (transformFunction, field(s) to set)
    // Caution: by definition, a TransformMap loses type safety. Always unit test!
    val transformMap: TransformMap =
      Map(("e"       , (EE.extractEventType, "event")),
          ("ip"      , (ME.identity, "user_ipaddress")),
          ("aid"     , (ME.identity, "app_id")),
          ("p"       , (ME.extractPlatform, "platform")),
          ("tid"     , (ME.identity, "txn_id")),
          ("uid"     , (ME.identity, "user_id")),
          ("duid"    , (ME.identity, "domain_userid")),
          ("nuid"    , (ME.identity, "network_userid")),
          ("fp"      , (ME.identity, "user_fingerprint")),
          ("vid"     , (CU.stringToJInteger, "domain_sessionidx")),
          ("dtm"     , (EE.extractTimestamp, "dvce_tstamp")),
          ("tv"      , (ME.identity, "v_tracker")),
          ("cv"      , (ME.identity, "v_collector")),
          ("lang"    , (ME.identity, "br_lang")),
          ("f_pdf"   , (CU.stringToJByte, "br_features_pdf")),
          ("f_fla"   , (CU.stringToJByte, "br_features_flash")),
          ("f_java"  , (CU.stringToJByte, "br_features_java")),
          ("f_dir"   , (CU.stringToJByte, "br_features_director")),
          ("f_qt"    , (CU.stringToJByte, "br_features_quicktime")),
          ("f_realp" , (CU.stringToJByte, "br_features_realplayer")),
          ("f_wma"   , (CU.stringToJByte, "br_features_windowsmedia")),
          ("f_gears" , (CU.stringToJByte, "br_features_gears")),
          ("f_ag"    , (CU.stringToJByte, "br_features_silverlight")),
          ("cookie"  , (CU.stringToJByte, "br_cookies")),
          ("res"     , (CE.extractViewDimensions, ("dvce_screenwidth", "dvce_screenheight"))), // Note tuple target
          ("cd"      , (ME.identity, "br_colordepth")),
          ("tz"      , (decodeString, "os_timezone")),
          ("refr"    , (decodeString, "page_referrer")),
          ("url"     , (decodeString, "page_url")), // Note we may override this below
          ("page"    , (decodeString, "page_title")),
          ("cs"      , (ME.identity, "doc_charset")),
          ("ds"      , (CE.extractViewDimensions, ("doc_width", "doc_height"))),
          ("vp"      , (CE.extractViewDimensions, ("br_viewwidth", "br_viewheight"))),
          // Custom structured events
          ("ev_ca"   , (decodeString, "se_category")),   // LEGACY tracker var. TODO: Remove in late 2013
          ("ev_ac"   , (decodeString, "se_action")),     // LEGACY tracker var. TODO: Remove in late 2013
          ("ev_la"   , (decodeString, "se_label")),      // LEGACY tracker var. TODO: Remove in late 2013
          ("ev_pr"   , (decodeString, "se_property")),   // LEGACY tracker var. TODO: Remove in late 2013
          ("ev_va"   , (CU.stringToDoublelike, "se_value")), // LEGACY tracker var. TODO: Remove in late 2013
          ("se_ca"   , (decodeString, "se_category")),
          ("se_ac"   , (decodeString, "se_action")),
          ("se_la"   , (decodeString, "se_label")),
          ("se_pr"   , (decodeString, "se_property")),
          ("se_va"   , (CU.stringToDoublelike, "se_value")),
          // Ecommerce transactions
          ("tr_id"   , (decodeString, "tr_orderid")),
          ("tr_af"   , (decodeString, "tr_affiliation")),
          ("tr_tt"   , (decodeString, "tr_total")),
          ("tr_tx"   , (decodeString, "tr_tax")),
          ("tr_sh"   , (decodeString, "tr_shipping")),
          ("tr_ci"   , (decodeString, "tr_city")),
          ("tr_st"   , (decodeString, "tr_state")),
          ("tr_co"   , (decodeString, "tr_country")),
          // Ecommerce transaction items
          ("ti_id"   , (decodeString, "ti_orderid")),
          ("ti_sk"   , (decodeString, "ti_sku")),
          ("ti_na"   , (decodeString, "ti_name")),
          ("ti_ca"   , (decodeString, "ti_category")),
          ("ti_pr"   , (decodeString, "ti_price")),
          ("ti_qu"   , (decodeString, "ti_quantity")),
          // Page pings
          ("pp_mix"  , (CU.stringToJInteger, "pp_xoffset_min")),
          ("pp_max"  , (CU.stringToJInteger, "pp_xoffset_max")),
          ("pp_miy"  , (CU.stringToJInteger, "pp_yoffset_min")),
          ("pp_may"  , (CU.stringToJInteger, "pp_yoffset_max")))

    val sourceMap: SourceMap = parameters.map(p => (p.getName -> p.getValue)).toList.toMap
  
    val transform = event.transform(sourceMap, transformMap)

    // Potentially update the page_url and set the page URL components
    val pageUri = PE.extractPageUri(raw.refererUri, Option(event.page_url))
    for (uri <- pageUri; u <- uri) {
      // Update the page_url
      event.page_url = u.toString

      // Set the URL components
      val components = CU.explodeUri(u)
      event.page_urlscheme = components.scheme
      event.page_urlhost = components.host
      event.page_urlport = components.port
      event.page_urlpath = components.path.orNull
      event.page_urlquery = components.query.orNull
      event.page_urlfragment = components.fragment.orNull
    }

    // Get the geo-location from the IP address
    val geoLocation = GE.extractGeoLocation(geo, event.user_ipaddress)
    for (loc <- geoLocation; l <- loc) {
      event.geo_country = l.countryCode
      event.geo_region = l.region.orNull
      event.geo_city = l.city.orNull
      event.geo_zipcode = l.postalCode.orNull
      event.geo_latitude = l.latitude
      event.geo_longitude = l.longitude
    }

    // Potentially set the referrer details and URL components
    val refererUri = CU.stringToUri(event.page_referrer)
    for (uri <- refererUri; u <- uri) {
      
      // Set the URL components
      val components = CU.explodeUri(u)
      event.refr_urlscheme = components.scheme
      event.refr_urlhost = components.host
      event.refr_urlport = components.port
      event.refr_urlpath = components.path.orNull
      event.refr_urlquery = components.query.orNull
      event.refr_urlfragment = components.fragment.orNull

      // Set the referrer details
      for (refr <- AE.extractRefererDetails(u, event.page_urlhost)) {
        event.refr_medium = refr.medium.toString
        event.refr_source = refr.source.orNull
        event.refr_term = refr.term.orNull
      }
    }

    // Marketing attribution
    val campaign = pageUri.fold(
      e => unitSuccessNel, // No fields updated
      uri => uri match {
        case Some(u) =>
          AE.extractMarketingFields(u, raw.encoding).flatMap(cmp => {
            event.mkt_medium = cmp.medium
            event.mkt_source = cmp.source
            event.mkt_term = cmp.term
            event.mkt_content = cmp.content
            event.mkt_campaign = cmp.campaign
            cmp.success
            })
        case None => unitSuccessNel // No fields updated
        })

    // Some quick and dirty truncation to ensure the load into Redshift doesn't error. Yech this is pretty dirty
    // TODO: move this into the db-specific ETL phase (when written) & _programmatically_ apply to all strings, not just these 6
    event.useragent = CU.truncate(event.useragent, 1000)
    event.page_title = CU.truncate(event.page_title, 2000)
    event.page_urlpath = CU.truncate(event.page_urlpath, 1000)
    event.page_urlquery = CU.truncate(event.page_urlquery, 3000)
    event.page_urlfragment = CU.truncate(event.page_urlfragment, 255)
    event.refr_urlpath = CU.truncate(event.refr_urlpath, 1000)
    event.refr_urlquery = CU.truncate(event.refr_urlquery, 3000)
    event.refr_urlfragment = CU.truncate(event.refr_urlfragment, 255)

    // Collect our errors on Failure, or return our event on Success 
    (useragent.toValidationNel |@| client.toValidationNel |@| pageUri.toValidationNel |@| geoLocation.toValidationNel |@| refererUri.toValidationNel |@| transform |@| campaign) {
      (_,_,_,_,_,_,_) => event
    }
  }
}
