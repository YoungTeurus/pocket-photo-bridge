package dev.pocketphotobridge.sony

import org.junit.Assert.*
import org.junit.Test

class SonyXmlTest {
    @Test fun actionNamesExcludeArgumentNamesInNamespacedServiceDescriptions() {
        val xml = """
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
                <specVersion><major>1</major><minor>0</minor></specVersion>
                <actionList>
                    <action><name>X_TransferStart</name></action>
                    <action><name>X_TransferEnd</name><argumentList>
                        <argument><name>ErrCode</name><direction>in</direction>
                            <relatedStateVariable>A_ARG_TYPE_ErrCode</relatedStateVariable></argument>
                    </argumentList></action>
                </actionList>
                <serviceStateTable><stateVariable sendEvents="no"><name>A_ARG_TYPE_ErrCode</name>
                    <dataType>ui4</dataType></stateVariable></serviceStateTable>
            </scpd>
        """
        val expected = setOf("X_TransferStart", "X_TransferEnd")
        assertEquals(expected, SonyXml.actions(xml))
        assertEquals(expected, SonyXml.actions(xml.replace("xmlns=\"urn:schemas-upnp-org:service-1-0\"", "")))
        val prefixed = xml.replace("xmlns=", "xmlns:p=")
            .replace(Regex("<(\\/?)([A-Za-z]+)(?=[\\s>])"), "<$1p:$2")
        assertEquals(expected, SonyXml.actions(prefixed))
    }

    @Test fun argumentNameCannotSubstituteForMissingActionName() {
        rejects("<action><argumentList><argument><name>X_TransferEnd</name></argument></argumentList></action>")
    }

    @Test fun duplicateDirectActionNamesAreRejected() {
        rejects("<action><name>X_TransferStart</name><name>X_TransferEnd</name></action>")
    }

    private fun rejects(action: String) {
        try {
            SonyXml.actions("<scpd><actionList>$action</actionList></scpd>")
            fail("Expected invalid service description")
        } catch (e: ProtocolFailure) {
            assertEquals(Failure.INVALID_XML, e.failure)
        }
    }
}
