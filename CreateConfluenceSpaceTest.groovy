import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import com.atlassian.sal.api.net.ReturningResponseHandler
import groovy.json.JsonBuilder
import org.apache.log4j.Logger
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.ApplicationType
import com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType

def log = Logger.getLogger("com.atlassian.scriptrunner.CreateConfluenceSpaceTest")

// Test values - modify these as needed
def testValues = [
    spaceName: "Test Space Name",
    spaceKey: "TEST" + System.currentTimeMillis().toString().take(4), // Ensures unique key
    isSecure: true
]

log.info("Starting test with values: ${testValues}")

// Prepare the space creation payload
def jsonBuilder = new JsonBuilder()
jsonBuilder {
    key testValues.spaceKey
    name testValues.spaceName
    type "global"
    description {
        plain {
            value "Space created from ScriptRunner console test"
            representation "plain"
        }
    }
    permissions {
        if (testValues.isSecure) {
            "space-permissions" ([
                "user-permissions": [
                    [
                        "type": "user",
                        "username": "admin",
                        "permissions": ["ADMIN"]
                    ]
                ]
            ])
        }
    }
}

// Get the Confluence application link
def applicationLinkService = ComponentAccessor.getOSGiComponentInstanceOfType(ApplicationLinkService.class)

// Directly use ConfluenceApplicationType.class, not an instance fetched from OSGi
def confluenceLink = applicationLinkService.getPrimaryApplicationLink(ConfluenceApplicationType.class)

if (!confluenceLink) {
    log.error("No Confluence application link found using ConfluenceApplicationType.class")
    return "ERROR: No Confluence application link found using ConfluenceApplicationType.class"
}

// Create the HTTP request
def httpClient = confluenceLink.createAuthenticatedRequestFactory().createRequest(Request.MethodType.POST, "/rest/api/space")
httpClient.setHeader("Content-Type", "application/json")
httpClient.setRequestBody(jsonBuilder.toString())

// Execute the request
try {
    def response = httpClient.execute(new ReturningResponseHandler<Response, Response>() {
        @Override
        Response handle(Response localResponse) throws ResponseException {
            if (localResponse.statusCode != 200) {
                log.error("Failed to create Confluence space. Status: ${localResponse.statusCode}, Response: ${localResponse.responseBodyAsString}")
                throw new ResponseException("Failed to create Confluence space")
            }
            return localResponse
        }
    } as com.atlassian.sal.api.net.ResponseHandler)
    
    def result = "Successfully created Confluence space:\n" +
                 "Space Name: ${testValues.spaceName}\n" +
                 "Space Key: ${testValues.spaceKey}\n" +
                 "Secure Space: ${testValues.isSecure}"
    
    log.info(result)
    return result
    
} catch (Exception e) {
    def errorMessage = "Error creating Confluence space: ${e.message}"
    log.error(errorMessage, e)
    return "ERROR: " + errorMessage
} 
