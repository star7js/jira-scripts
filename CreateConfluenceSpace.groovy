import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import com.atlassian.sal.api.net.ReturningResponseHandler
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType

import groovy.json.JsonBuilder
import org.apache.log4j.Logger

// Use a unique logger name to differentiate from other versions if necessary
def log = Logger.getLogger("com.atlassian.scriptrunner.CreateConfluenceSpace_Resources") // Changed logger name

log.info("CreateConfluenceSpace_Resources script started.")

// In a ScriptRunner post-function, 'issue' is injected into the binding.
if (!binding.hasVariable('issue')) {
    log.error("Issue object not found in binding. This script must be run as a post-function.")
    return
}

Issue issue = binding.getProperty('issue') as Issue // Corrected from binding.get('issue')

if (!issue) {
    log.error("Issue object (from binding) is null or not of type Issue.")
    return
}

log.info("Processing issue: ${issue.key}")

// --- Actual Logic Starts Here ---
def customFieldManager = ComponentAccessor.customFieldManager

final String SPACE_NAME_FIELD_NAME = "Confluence Space Name"
final String SPACE_KEY_FIELD_NAME = "Confluence Space Key"
final String OPSEC_FIELD_NAME = "Is OPSEC Space"

def spaceNameField = customFieldManager.getCustomFieldObjects(issue).find { it.name == SPACE_NAME_FIELD_NAME }
def spaceKeyField = customFieldManager.getCustomFieldObjects(issue).find { it.name == SPACE_KEY_FIELD_NAME }
def opsecField = customFieldManager.getCustomFieldObjects(issue).find { it.name == OPSEC_FIELD_NAME }

def spaceName = spaceNameField ? issue.getCustomFieldValue(spaceNameField)?.toString() : null
def spaceKey = spaceKeyField ? issue.getCustomFieldValue(spaceKeyField)?.toString() : null

def opsecFieldValue = opsecField ? issue.getCustomFieldValue(opsecField) : null
boolean isOpsec = false
if (opsecFieldValue instanceof Collection) {
    isOpsec = opsecFieldValue.any { it?.toString()?.equalsIgnoreCase("Yes") || it?.toString()?.equalsIgnoreCase("True") }
} else if (opsecFieldValue != null) {
    isOpsec = opsecFieldValue.toString().equalsIgnoreCase("Yes") || opsecFieldValue.toString().equalsIgnoreCase("True")
}

if (!spaceName || !spaceKey) {
    log.error("Required fields ('${SPACE_NAME_FIELD_NAME}' or '${SPACE_KEY_FIELD_NAME}') missing for issue ${issue.key}")
    return
}

log.info("Preparing to create Confluence space for issue ${issue.key}. Name: '${spaceName}', Key: '${spaceKey}', OPSEC: ${isOpsec}")

def jsonBuilder = new JsonBuilder()
jsonBuilder {
    key spaceKey
    name spaceName
    type "global"
    description {
        plain {
            value "Space created automatically from Jira issue ${issue.key}"
            representation "plain"
        }
    }
    if (isOpsec) {
        permissions {
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

def payload = jsonBuilder.toString()
log.debug("Payload: ${payload}")

def applicationLinkService = ComponentAccessor.getOSGiComponentInstanceOfType(ApplicationLinkService.class)
def confluenceLink = applicationLinkService.getPrimaryApplicationLink(ConfluenceApplicationType.class)

if (!confluenceLink) {
    log.error("No primary Confluence application link found.")
    return
}
log.debug("Found Confluence link: ${confluenceLink.name}")

def httpClient = confluenceLink.createAuthenticatedRequestFactory().createRequest(Request.MethodType.POST, "/rest/api/space")
httpClient.setHeader("Content-Type", "application/json")
httpClient.setRequestBody(payload)

def commentManager = ComponentAccessor.commentManager
def currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
try {
    log.info("Executing Confluence space creation request...")
    httpClient.execute(new ReturningResponseHandler<Response, Response>() {
        @Override
        Response handle(Response localResponse) throws ResponseException {
            log.debug("Confluence response status: ${localResponse.statusCode}")
            if (localResponse.statusCode < 200 || localResponse.statusCode >= 300) {
                String errorBody = localResponse.responseBodyAsString
                log.error("Failed to create Confluence space. Status: ${localResponse.statusCode}, Response: ${errorBody}")
                throw new ResponseException("Confluence space creation failed. Status: ${localResponse.statusCode}. Details: ${errorBody.take(500)}")
            }
            return localResponse
        }
    } as com.atlassian.sal.api.net.ResponseHandler)

    def confluenceBaseUrl = confluenceLink.displayUrl.toString() // Get Confluence base URL
    // Ensure base URL doesn't end with a slash for clean concatenation
    if (confluenceBaseUrl.endsWith("/")) {
        confluenceBaseUrl = confluenceBaseUrl.substring(0, confluenceBaseUrl.length() - 1)
    }
    def confluenceSpaceUrl = "${confluenceBaseUrl}/display/${spaceKey}"

    String successComment = "Confluence space '[${spaceName}|${confluenceSpaceUrl}]' (key: ${spaceKey}) created successfully. OPSEC: ${isOpsec}. Triggered by issue ${issue.key}."
    log.info(successComment)
    commentManager.create(issue, currentUser, successComment, false)

} catch (Exception e) {
    String errorComment = "Error creating Confluence space for issue ${issue.key}: ${e.getMessage()}"
    log.error(errorComment, e)
    commentManager.create(issue, currentUser, errorComment, false)
}
log.info("CreateConfluenceSpace_Resources script finished for issue ${issue.key}.") 
