import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.event.issue.IssueEvent
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.ApplicationLinkRequestFactory
import com.atlassian.applinks.api.ApplicationLinkResponseHandler
import com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType // For finding the Confluence link
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.log4j.Logger
import org.apache.log4j.Level

// --- Configuration ---
// Adjust these as needed
final String TARGET_PROJECT_KEY = "PROJ" // Only run for issues in this project
final String TARGET_ISSUE_TYPE_NAME = "Task" // Only for this issue type
final String SPACE_NAME_CUSTOM_FIELD_NAME = "Space Name" // Name of the custom field holding the desired space name
final String SPACE_KEY_CUSTOM_FIELD_NAME = "Space Key" // Optional: Name of CF for space key. If null, key is derived.
// final String CONFLUENCE_APP_LINK_NAME = "My Confluence Link" // Optional: if you have multiple Confluence links and want to specify one by name

def log = Logger.getLogger("com.example.ConfluenceSpaceCreatorListener")
log.setLevel(Level.INFO) // Set to DEBUG for more verbose logging

// --- Get Event and Issue ---
IssueEvent event = event // 'event' is automatically injected into listeners
def issue = event.getIssue()

if (!issue) {
    log.debug("No issue found in event. Exiting.")
    return
}

// --- Check Conditions (Project, Issue Type) ---
if (issue.projectObject.key != TARGET_PROJECT_KEY) {
    log.debug("Issue ${issue.key} is not in project ${TARGET_PROJECT_KEY}. Skipping.")
    return
}
if (issue.issueType.name != TARGET_ISSUE_TYPE_NAME) {
    log.debug("Issue ${issue.key} is not of type ${TARGET_ISSUE_TYPE_NAME}. Skipping.")
    return
}

// --- Get Space Details from Custom Fields ---
def customFieldManager = ComponentAccessor.getCustomFieldManager()

def spaceNameCf = customFieldManager.getCustomFieldObjects(issue).find { it.name == SPACE_NAME_CUSTOM_FIELD_NAME }
if (!spaceNameCf) {
    log.error("Custom field '${SPACE_NAME_CUSTOM_FIELD_NAME}' not found. Cannot create space.")
    return
}
String spaceName = issue.getCustomFieldValue(spaceNameCf) as String
if (!spaceName || spaceName.trim().isEmpty()) {
    log.info("Space Name custom field is empty for issue ${issue.key}. Skipping space creation.")
    return
}

String spaceKey
if (SPACE_KEY_CUSTOM_FIELD_NAME) {
    def spaceKeyCf = customFieldManager.getCustomFieldObjects(issue).find { it.name == SPACE_KEY_CUSTOM_FIELD_NAME }
    if (spaceKeyCf) {
        spaceKey = issue.getCustomFieldValue(spaceKeyCf) as String
    }
}

if (!spaceKey || spaceKey.trim().isEmpty()) {
    // Auto-generate space key from name if not provided or empty
    spaceKey = spaceName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase()
    if (spaceKey.length() > 10) { // Confluence keys can be longer, but often kept short
        spaceKey = spaceKey.substring(0, 10)
    }
    // Ensure key is not empty after stripping characters
    if (spaceKey.isEmpty()) {
        // Fallback if name had no alphanumeric chars, e.g., use project key + issue number
        spaceKey = (issue.projectObject.key + issue.number).toUpperCase()
        log.warn("Generated space key '${spaceKey}' as fallback for issue ${issue.key}")
    }
    log.info("Auto-generated space key '${spaceKey}' from space name '${spaceName}' for issue ${issue.key}.")
}

// Validate space key (basic)
if (!spaceKey.matches("^[A-Z0-9]+$")) {
    log.error("Generated space key '${spaceKey}' is invalid (must be uppercase alphanumeric). Cannot create space for issue ${issue.key}.")
    // Optionally, add a comment to the issue here
    return
}

// --- Get Confluence Application Link ---
def applicationLinkService = ComponentAccessor.getOSGiComponentInstanceOfType(ApplicationLinkService.class)
// Get the primary Confluence link. If you have multiple, you might need to iterate or get by name/ID.
def confluenceLink = applicationLinkService.getPrimaryApplicationLink(ConfluenceApplicationType.class)

/*
// Alternative: If you want to specify by name (uncomment and use if needed)
if (CONFLUENCE_APP_LINK_NAME) {
    confluenceLink = applicationLinkService.getApplicationLinks().find { it.name == CONFLUENCE_APP_LINK_NAME && it.type instanceof ConfluenceApplicationType }
}
*/

if (!confluenceLink) {
    log.error("No primary Confluence application link found. Configure Application Links in Jira. Cannot create space.")
    return
}
log.info("Using Confluence Application Link: ${confluenceLink.name} (${confluenceLink.displayUrl})")

// --- Create Authenticated Request Factory ---
ApplicationLinkRequestFactory requestFactory = confluenceLink.createAuthenticatedRequestFactory()

// --- Prepare Confluence API Request ---
def spacePayload = [
    key : spaceKey,
    name: spaceName,
    description: [ // Optional description
        plain: [
            value: "Space created automatically from Jira issue ${issue.key}: ${issue.summary}",
            representation: "plain"
        ]
    ]
    // You can add more properties here, e.g., permissions, if needed
    // "permissions": [ ... ] // This is more complex
]
String jsonPayload = JsonOutput.toJson(spacePayload)
log.debug("Confluence space creation payload: ${jsonPayload}")

try {
    Request confluencerequest = requestFactory.createRequest(Request.MethodType.POST, "/rest/api/space")
    confluencerequest.addHeader("Content-Type", "application/json")
    confluencerequest.addHeader("Accept", "application/json")
    confluencerequest.setRequestBody(jsonPayload)

    // --- Execute Request and Handle Response ---
    // Using executeAndReturn is simpler if you just need the body as string
    String responseString = confluencerequest.executeAndReturn(new ApplicationLinkResponseHandler<String>() {
        @Override
        String credentialsRequired(Response response) throws ResponseException {
            log.error("Credentials required for Confluence API call: ${response.statusCode} - ${response.statusText}")
            throw new ResponseException("Credentials required")
        }

        @Override
        String handle(Response response) throws ResponseException {
            if (response.isSuccessful()) {
                log.info("Successfully received response from Confluence space creation.")
                return response.getResponseBodyAsString()
            } else {
                String errorBody = "No error body available."
                try {
                    errorBody = response.getResponseBodyAsString()
                } catch (ResponseException e) {
                    log.warn("Could not read error response body: ${e.message}")
                }
                log.error("Failed to create Confluence space. Status: ${response.statusCode} ${response.statusText}. Body: ${errorBody}")
                throw new ResponseException("Failed to create space: ${response.statusCode} - ${response.statusText}")
            }
        }
    })

    log.info("Confluence API response: ${responseString}")
    def jsonResponse = new JsonSlurper().parseText(responseString)
    String spaceUrl = jsonResponse._links?.webui ?: (confluenceLink.displayUrl.toString() + jsonResponse._links?.self?.replace("/rest/api", "")) // Construct URL

    if (spaceUrl.endsWith('/')) spaceUrl = spaceUrl.substring(0, spaceUrl.length() -1) // remove trailing slash
    String confluenceSpaceUiLink = confluenceLink.displayUrl.toString() + "/spaces/" + spaceKey + "/overview"


    log.info("Successfully created Confluence space: ${spaceName} (${spaceKey}). Link: ${confluenceSpaceUiLink}")

    // --- Optional: Add a comment to the Jira issue ---
    def commentManager = ComponentAccessor.getCommentManager()
    def currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser() // Or a dedicated automation user
    String commentBody = "Confluence space created: [${spaceName}|${confluenceSpaceUiLink}]"
    commentManager.create(issue, currentUser, commentBody, false) // false = don't dispatch event for this comment

} catch (ResponseException e) {
    log.error("Error executing Confluence space creation request for issue ${issue.key}: ${e.getMessage()}", e)
    // Add a comment to the Jira issue indicating failure
    def commentManager = ComponentAccessor.getCommentManager()
    def currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    String commentBody = "Failed to create Confluence space for '${spaceName}'. Error: ${e.getMessage()}"
    commentManager.create(issue, currentUser, commentBody, false)
} catch (Exception e) {
    log.error("An unexpected error occurred while trying to create Confluence space for issue ${issue.key}: ${e.getMessage()}", e)
    // Add a comment to the Jira issue indicating failure
    def commentManager = ComponentAccessor.getCommentManager()
    def currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    String commentBody = "An unexpected error occurred trying to create Confluence space for '${spaceName}'. Check Jira logs for details."
    commentManager.create(issue, currentUser, commentBody, false)
}
