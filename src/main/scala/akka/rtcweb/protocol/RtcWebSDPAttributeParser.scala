package akka.rtcweb.protocol

import akka.parboiled2._
import akka.rtcweb.protocol.ice.parser.{ IceExtensionAttributeParser, CandidateParser }
import akka.rtcweb.protocol.sdp.ExtensionAttribute
import akka.rtcweb.protocol.sdp.grouping.parser.GroupParser
import akka.rtcweb.protocol.sdp.parser._
import akka.shapeless._

trait RtcWebSDPAttributeParser extends Parser
    with CommonSdpParser
    with SessionDescriptionParser
    with MediaParser
    with MediaAttributeExtensionRule
    with SessionAttributeExtensionRule
    with GroupParser
    with IceExtensionAttributeParser
    with CommonRules
    with StringBuilding
    with Base64Parsing {

  override def sessionAttributesExtensionsRule: Rule1[ExtensionAttribute] = rule { groupSessionAttributeExtensions | iceMediaAttributeExtensions }
  override def mediaAttributesExtensionsRule: Rule1[ExtensionAttribute] = rule { groupMediaAttributeExtensions | iceSessionAttributeExtensions }

}
