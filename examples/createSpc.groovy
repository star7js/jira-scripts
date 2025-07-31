import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import com.atlassian.sal.api.net.ResponseHandler
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

// Helper function to get the primary Confluence application link
static ApplicationLink getPrimaryConfluenceLink() {
    def applicationLinkService = ComponentLocator.getComponent(ApplicationLinkService)
    applicationLinkService.getPrimaryApplicationLink(ConfluenceApplicationType)
}

// Get the issue from the binding (assuming this runs in a post-function or listener with issue available)
def issue = issue // This is provided in ScriptRunner bindings for post-functions

// Get CustomFieldManager
def cfManager = ComponentAccessor.getCustomFieldManager()

// Retrieve custom field values - replace with actual field names if different
def spaceNameCf = cfManager.getCustomFieldObjectsByName("Desired Space Name")?.first()
def spaceKeyCf = cfManager.getCustomFieldObjectsByName("Desired Space Key")?.first()
def descCf = cfManager.getCustomFieldObjectsByName("Describe the New Space")?.first()
def usageCf = cfManager.getCustomFieldObjectsByName("What will the Space be used for and by who?")?.first()
def allEmployeesCf = cfManager.getCustomFieldObjectsByName("Should All Employees Have The Ability To View This Space?")?.first()
def teamAdminsCf = cfManager.getCustomFieldObjectsByName("Team Members who should have Space Admin")?.first() // Assuming multi-user picker

// Get values
def spaceName = issue.getCustomFieldValue(spaceNameCf) as String
def spaceKey = issue.getCustomFieldValue(spaceKeyCf) as String
def description = issue.getCustomFieldValue(descCf) as String ?: ""
def usage = issue.getCustomFieldValue(usageCf) as String ?: ""
def fullDesc = "${description}\n${usage}".trim()
def allEmployeesView = issue.getCustomFieldValue(allEmployeesCf) == "Yes"
def teamAdmins = issue.getCustomFieldValue(teamAdminsCf) as List ?: [] // List of ApplicationUser
def requester = issue.getReporter() // ApplicationUser

// Exit if required fields are missing
if (!spaceName || !spaceKey) {
    log.error("Missing required fields: Desired Space Name or Desired Space Key")
    return
}

// Get Confluence link
def confluenceLink = getPrimaryConfluenceLink()
if (!confluenceLink) {
    log.error("No primary Confluence application link found")
    return
}

def authenticatedRequestFactory = confluenceLink.createImpersonatingAuthenticatedRequestFactory()

// Create the space as private (only creator has access initially)
def createSpaceParams = [
    key: spaceKey,
    name: spaceName,
    description: [
        plain: [
            value: fullDesc,
            representation: "plain"
        ]
    ]
]

def createResponseBody = null
try {
    authenticatedRequestFactory
        .createRequest(Request.MethodType.POST, "rest/api/space/_private")
        .addHeader("Content-Type", "application/json")
        .setRequestBody(new JsonBuilder(createSpaceParams).toString())
        .execute(new ResponseHandler<Response>() {
            @Override
            void handle(Response response) throws ResponseException {
                if (response.statusCode != HttpURLConnection.HTTP_CREATED) {
                    throw new Exception("Failed to create space: ${response.getResponseBodyAsString()}")
                }
                createResponseBody = new JsonSlurper().parseText(response.getResponseBodyAsString())
            }
        })
} catch (Exception e) {
    log.error("Error creating Confluence space: ${e.message}")
    return
}

if (!createResponseBody) {
    log.error("Space creation failed without exception")
    return
}

// Space created successfully - now add permissions using JSON-RPC (since REST permission endpoint is not available in Server/DC)

// Helper function to call JSON-RPC
def callRpc(String method, List params) {
    def rpcBody = [
        jsonrpc: "2.0",
        method: method,
        params: params,
        id: System.currentTimeMillis()
    ]

    def rpcResponseBody = null
    try {
        authenticatedRequestFactory
            .createRequest(Request.MethodType.POST, "rpc/json-rpc/confluenceservice-v2")
            .addHeader("Content-Type", "application/json")
            .setRequestBody(new JsonBuilder(rpcBody).toString())
            .execute(new ResponseHandler<Response>() {
                @Override
                void handle(Response response) throws ResponseException {
                    if (!response.isSuccessful()) {
                        throw new Exception("RPC call failed: ${response.statusCode} - ${response.getResponseBodyAsString()}")
                    }
                    rpcResponseBody = new JsonSlurper().parseText(response.getResponseBodyAsString())
                }
            })

        if (rpcResponseBody.error) {
            log.warn("RPC error: ${rpcResponseBody.error.message}")
            return false
        }
        return rpcResponseBody.result as boolean
    } catch (Exception e) {
        log.error("Error in RPC call to ${method}: ${e.message}")
        return false
    }
}

// Helper function to add permission
def addPermission(String permission, String entityName, String spaceKey) {
    // permission e.g. "ADMINISTER" or "VIEWSPACE"
    // entityName: group name or username
    return callRpc("addPermissionToSpace", [permission, entityName, spaceKey])
}

// Add administer permissions for team members (users)
teamAdmins.each { adminUser ->
    addPermission("ADMINISTER", adminUser.getUsername(), spaceKey)
}

// Add view permission for requester (if not already in admins)
if (requester && !teamAdmins.contains(requester)) {
    addPermission("VIEWSPACE", requester.getUsername(), spaceKey)
}

// If all employees should view, add view permission for the default group (adjust group name if different, e.g., "confluence-users")
if (allEmployeesView) {
    addPermission("VIEWSPACE", "confluence-users", spaceKey)
}

// Optionally, link the space back to the Jira issue or log the space URL
def spaceUrl = createResponseBody._links?.webui ?: "/spaces/${spaceKey}"
log.info("Confluence space created: ${spaceName} (${spaceKey}) at ${confluenceLink.getDestinationApplicationUrl()}${spaceUrl}")