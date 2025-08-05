import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.ApplicationLinkRequestFactory
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import com.atlassian.sal.api.net.ResponseHandler
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.Field

// Helper function to get the primary Confluence application link
static ApplicationLink getPrimaryConfluenceLink() {
    def applicationLinkService = ComponentLocator.getComponent(ApplicationLinkService)
    applicationLinkService.getPrimaryApplicationLink(ConfluenceApplicationType)
}

// Get the issue from the binding (assuming this runs in a post-function or listener with issue available)
// def issue = issue // This is provided in ScriptRunner bindings for post-functions
// For testing:
def issue = Issues.getByKey("PRC-10") // Comment out for production

// Get CustomFieldManager
def cfManager = ComponentAccessor.getCustomFieldManager()

// Retrieve custom field values - replace with actual field names if different
def spaceNameCf = cfManager.getCustomFieldObjectsByName("Desired Space Name")?.first()
def spaceKeyCf = cfManager.getCustomFieldObjectsByName("Desired Space Key")?.first()
def teamAdminsCf = cfManager.getCustomFieldObjectsByName("Team Members who should have Space Admin")?.first() // Assuming multi-user picker

// Get values
def spaceName = issue.getCustomFieldValue(spaceNameCf) as String
def spaceKey = issue.getCustomFieldValue(spaceKeyCf) as String
def fullDesc = "Created from Jira issue ${issue.key}".trim()
def teamAdmins = issue.getCustomFieldValue(teamAdminsCf) as List<ApplicationUser> ?: [] // List of ApplicationUser
def requester = issue.getReporter() // ApplicationUser

// Get the current user running the script
def currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser() ?: requester // Fallback to requester if no current user

// Exit if required fields are missing
if (!spaceName || !spaceKey) {
    log.error("Missing required fields: Desired Space Name or Desired Space Key")
    return
}

// Get Confluence link
@Field
ApplicationLink confluenceLink

@Field
ApplicationLinkRequestFactory authenticatedRequestFactory

confluenceLink = getPrimaryConfluenceLink()
if (!confluenceLink) {
    log.error("No primary Confluence application link found")
    return
}

authenticatedRequestFactory = confluenceLink.createImpersonatingAuthenticatedRequestFactory()

// Create the space (non-private, so it inherits default permissions)
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

def createResponseBody = null as Map<String, Object>
try {
    authenticatedRequestFactory
            .createRequest(Request.MethodType.POST, "rest/api/space")
            .addHeader("Content-Type", "application/json")
            .setRequestBody(new JsonBuilder(createSpaceParams).toString())
            .execute(new ResponseHandler<Response>() {
                @Override
                void handle(Response response) throws ResponseException {
                    if (!response.isSuccessful()) {
                        throw new Exception("Failed to create space: ${response.getResponseBodyAsString()}")
                    }
                    createResponseBody = new JsonSlurper().parseText(response.getResponseBodyAsString()) as Map<String, Object>
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
    def rpcResponseBody = null as Map<String, Object>
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
                        rpcResponseBody = new JsonSlurper().parseText(response.getResponseBodyAsString()) as Map<String, Object>
                    }
                })
        log.info("RPC response for method ${method}: ${rpcResponseBody}") // Added for debugging
        if (rpcResponseBody['error']) {
            log.warn("RPC error: ${rpcResponseBody['error']['message']}")
            return false
        }
        return rpcResponseBody['result'] as boolean
    } catch (Exception e) {
        log.error("Error in RPC call to ${method}: ${e.message}")
        return false
    }
}

// Helper function to add permission
def addPermission(String permission, String entityName, String spaceKey) {
    // permission e.g. "VIEWSPACE" or "SETSPACEPERMISSIONS"
    // entityName: group name or username
    def success = callRpc("addPermissionToSpace", [permission, entityName, spaceKey])
    log.info("Add ${permission} for ${entityName} to ${spaceKey} succeeded: ${success}") // Added for debugging
    return success
}

// Add administer permissions for team members (users)
teamAdmins.each { ApplicationUser adminUser ->
    addPermission("VIEWSPACE", adminUser.getUsername(), spaceKey)
    addPermission("SETSPACEPERMISSIONS", adminUser.getUsername(), spaceKey)
}

// Make requester an admin (always, in addition to team admins)
if (requester) {
    addPermission("VIEWSPACE", requester.getUsername(), spaceKey)
    addPermission("SETSPACEPERMISSIONS", requester.getUsername(), spaceKey)
}

// Get the space URL
def links = createResponseBody['_links'] as Map<String, Object>
def spaceUrl = links?.webui ?: "/spaces/${spaceKey}"
def confluenceBaseUrl = confluenceLink.getDisplayUrl()
def fullSpaceUrl = "${confluenceBaseUrl}${spaceUrl}"

// Log the creation
log.info("Confluence space created: ${spaceName} (${spaceKey}) at ${fullSpaceUrl}")

// Add a comment to the Jira issue with the link to the new space
def commentManager = ComponentAccessor.getCommentManager()
def commentBody = "New Confluence space created: [${spaceName} (${spaceKey})|${fullSpaceUrl}]"
commentManager.create(issue, currentUser, commentBody, false) // Using current user as the comment author
