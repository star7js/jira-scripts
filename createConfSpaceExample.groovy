import com.atlassian.jira.component.ComponentAccessor
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

//── 1. Grab the issue and your custom fields ───────────────────────────
def issue      = issue  // ScriptRunner binds `issue` in a post-function
def cfSummary  = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName("Summary of the Space")
def cfPurpose  = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName("Describe the purpose")
def cfSpaceName= ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName("New Desired Space Name")
def cfSpaceKey = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName("Desired Space Key")

def summary    = issue.getSummary()                                  // or issue.getCustomFieldValue(cfSummary)
def purpose    = issue.getCustomFieldValue(cfPurpose) as String ?: ""
def spaceName  = issue.getCustomFieldValue(cfSpaceName) as String
def spaceKey   = issue.getCustomFieldValue(cfSpaceKey)  as String

// Validate required inputs
if(!spaceName || !spaceKey) {
    log.error("Confluence-Space PostFn: name or key blank. name='${spaceName}', key='${spaceKey}'")
    return false
}

//── 2. Lookup your stored credentials ───────────────────────────────────
def secure    = ComponentAccessor.getOSGiComponentInstanceOfType(
    com.onresolve.scriptrunner.canned.common.admin.SecureFieldsService
)
def creds     = secure.get("confluence.apiToken")   // returns Map [username: "...", password: "..."]
def username  = creds.username
def apiToken  = creds.password

//── 3. Build the JSON payload ──────────────────────────────────────────
def payload = new JsonBuilder([
    key         : spaceKey,
    name        : spaceName,
    description : [
        plain: [
            value         : "${summary}\n\n${purpose}",
            representation: "plain"
        ]
    ]
]).toString()

//── 4. Fire the REST call to Confluence ─────────────────────────────────
def baseUrl = "https://confluence.mycompany.com/wiki"
def apiUrl  = "${baseUrl}/rest/api/space"

def conn = new URL(apiUrl).openConnection()
conn.setRequestMethod("POST")
conn.doOutput = true
conn.setRequestProperty("Content-Type", "application/json")
def auth = "${username}:${apiToken}".bytes.encodeBase64().toString()
conn.setRequestProperty("Authorization", "Basic ${auth}")

conn.outputStream.withWriter("UTF-8") { it << payload }

//── 5. Handle the response ─────────────────────────────────────────────
def code = conn.responseCode
if (code >= 200 && code < 300) {
    log.info("Confluence space '${spaceKey}' created successfully.")
    return true
}
else if (code == 409) {
    log.warn("Space key '${spaceKey}' already exists. Skipping creation.")
    return true
}
else {
    // read error body, log it, and fail the transition so the agent can triage
    def err = conn.errorStream?.getText("UTF-8")
    log.error("Failed to create Confluence space: HTTP $code — $err")
    return false
}