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

def createResponseBody
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

// Space created successfully - now add permissions

// Helper function to add permission
def addPermission(String subjectType, String identifier, String operationKey) {
    def permParams = [
        subject: [
            type: subjectType,
            identifier: identifier
        ],
        operation: [
            key: operationKey,
            target: "space"
        ]
    ]

    try {
        authenticatedRequestFactory
            .createRequest(Request.MethodType.POST, "rest/api/space/${spaceKey}/permission")
            .addHeader("Content-Type", "application/json")
            .setRequestBody(new JsonBuilder(permParams).toString())
            .execute(new ResponseHandler<Response>() {
                @Override
                void handle(Response response) throws ResponseException {
                    if (response.statusCode != HttpURLConnection.HTTP_OK) {
                        log.warn("Failed to add permission for ${identifier}: ${response.getResponseBodyAsString()}")
                    }
                }
            })
    } catch (Exception e) {
        log.error("Error adding permission: ${e.message}")
    }
}

// Add administer permissions for team members
teamAdmins.each { adminUser ->
    addPermission("user", adminUser.getUsername(), "administer")
}

// Add view permission for requester (if not already in admins)
if (requester && !teamAdmins.contains(requester)) {
    addPermission("user", requester.getUsername(), "read")
}

// If all employees should view, add view permission for the default group (adjust group name if different, e.g., "confluence-users")
if (allEmployeesView) {
    addPermission("group", "confluence-users", "read")
}

// Optionally, link the space back to the Jira issue or log the space URL
def spaceUrl = createResponseBody?._links?.webui
log.info("Confluence space created: ${spaceName} (${spaceKey}) at ${confluenceLink.getDestinationApplicationUrl()}${spaceUrl}")