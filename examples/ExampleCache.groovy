import com.atlassian.jira.bc.project.ProjectCreationData
import com.atlassian.jira.bc.project.ProjectService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.AssigneeTypes
import com.atlassian.jira.project.type.ProjectTypeKey
import com.atlassian.jira.security.roles.ProjectRoleManager
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.cluster.ClusterManager
import com.onresolve.scriptrunner.runner.customisers.JiraAgileBean
import com.onresolve.scriptrunner.runner.customisers.WithPlugin
import com.atlassian.greenhopper.web.rapid.view.RapidViewHelper
import org.apache.log4j.Level
import org.apache.log4j.Logger

@WithPlugin('com.pyxis.greenhopper.jira')
@JiraAgileBean
RapidViewHelper rapidViewHelper

def log = Logger.getLogger("com.acme.project.creation")
log.setLevel(Level.WARN)

def cfManager = ComponentAccessor.customFieldManager
def projectManager = ComponentAccessor.projectManager
def projectService = ComponentAccessor.getComponent(ProjectService)
def projectRoleManager = ComponentAccessor.getComponent(ProjectRoleManager)
def userManager = ComponentAccessor.userManager
def clusterManager = ComponentAccessor.getComponent(ClusterManager)
def loggedInUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
def commentManager = ComponentAccessor.commentManager
def applicationProperties = ComponentAccessor.applicationProperties

def nodeId = clusterManager.nodeId ?: "single-node"
log.warn("Script starting on node: ${nodeId} for issue ${issue.key}")

// Get custom field values with null checks (updated with actual field IDs)
def nameCf = cfManager.getCustomFieldObject("customfield_10100") // New Project Or Team Space
if (!nameCf) {
    log.error("Custom field with ID customfield_10100 not found")
    throw new Exception("Custom field missing: New Project Or Team Space")
}
def projectName = issue.getCustomFieldValue(nameCf) as String
log.warn("Project name: ${projectName}")

def typeCf = cfManager.getCustomFieldObject("customfield_10101") // Project Type
if (!typeCf) {
    log.error("Custom field with ID customfield_10101 not found")
    throw new Exception("Custom field missing: Project Type")
}
def projectTypeStr = issue.getCustomFieldValue(typeCf) as String
log.warn("Project type string: ${projectTypeStr}")

def leadCf = cfManager.getCustomFieldObject("customfield_10103") // Project Lead / Project Admin
if (!leadCf) {
    log.error("Custom field with ID customfield_10103 not found")
    throw new Exception("Custom field missing: Project Lead / Project Admin")
}
def lead = issue.getCustomFieldValue(leadCf) as ApplicationUser
log.warn("Project lead: ${lead?.username}")

def adminsCf = cfManager.getCustomFieldObject("customfield_10104") // List All Users That Should Have Administrator Rights On The Project
if (!adminsCf) {
    log.error("Custom field with ID customfield_10104 not found")
    throw new Exception("Custom field missing: List All Users That Should Have Administrator Rights On The Project")
}
def adminUsers = issue.getCustomFieldValue(adminsCf) as List<ApplicationUser> ?: []
log.warn("Additional admins: ${adminUsers.collect { it.username }}")

def sdCf = cfManager.getCustomFieldObject("customfield_10105") // List All Users That Should Have Service Desk Team Rights On The Project
if (!sdCf) {
    log.error("Custom field with ID customfield_10105 not found")
    throw new Exception("Custom field missing: List All Users That Should Have Service Desk Team Rights On The Project")
}
def sdUsers = issue.getCustomFieldValue(sdCf) as List<ApplicationUser> ?: []
log.warn("Service desk users: ${sdUsers.collect { it.username }}")

def jipCf = cfManager.getCustomFieldObject("customfield_10301") // Will This Project Be Used With JIP Or Jira Integration Plus
if (!jipCf) {
    log.error("Custom field with ID customfield_10301 not found")
    throw new Exception("Custom field missing: Will This Project Be Used With JIP Or Jira Integration Plus")
}
def jipYes = issue.getCustomFieldValue(jipCf)?.toString() == "Yes"
log.warn("JIP enabled: ${jipYes}")

if (!projectName || !projectTypeStr || !lead) {
    log.error("Missing required fields")
    throw new Exception("Missing project name, type, or lead")
}

// Map type string to Jira type key and board type (if software)
def typeKeyStr
def boardType = null
if (projectTypeStr.contains("Software")) {
    typeKeyStr = "software"
    boardType = projectTypeStr.contains("Scrum") ? "scrum" : "kanban"
} else if (projectTypeStr.contains("Business")) {
    typeKeyStr = "business"
} else if (projectTypeStr.contains("Service")) {
    typeKeyStr = "service_desk"
} else {
    log.error("Unknown project type: ${projectTypeStr}")
    throw new Exception("Unsupported project type")
}
def projectTypeKey = new ProjectTypeKey(typeKeyStr)
log.warn("Mapped to type key: ${typeKeyStr}, board: ${boardType}")

// Generate unique name
def baseName = projectName
def uniqueName = baseName
int nameSuffix = 1
while (projectManager.getProjectObjByName(uniqueName)) {
    log.warn("Name ${uniqueName} exists, trying ${baseName} (${nameSuffix})")
    uniqueName = "${baseName} (${nameSuffix})"
    nameSuffix++
}
log.warn("Generated unique name: ${uniqueName}")

// Generate unique key from baseName (not uniqueName) to avoid invalid chars from suffixes
def generateKey = { String name ->
    // Clean name: keep only letters/spaces, uppercase
    def cleanedName = name.replaceAll("[^A-Za-z ]", "").trim().toUpperCase()
    if (!cleanedName) {
        log.error("No valid letters in project name for key generation")
        throw new Exception("Invalid project name for key")
    }
    def words = cleanedName.split("\\s+")
    def baseKey = words.collect { it[0] }.join("")  // No ?.: assumes letters now
    // Fallback if too short: take first 2-5 letters
    if (baseKey.length() < 2) {
        baseKey = cleanedName.replaceAll(" ", "")[0..Math.min(4, cleanedName.length() - 1)]
    }
    // Ensure starts with letter (though cleaning should handle)
    if (!baseKey.matches("^[A-Z].*")) {
        log.error("Generated base key ${baseKey} doesn't start with a letter")
        throw new Exception("Invalid base key")
    }
    def key = baseKey
    int suffix = 1
    while (projectManager.getProjectByCurrentKey(key)) {
        log.warn("Key ${key} exists, trying ${baseKey + suffix}")
        key = baseKey + suffix
        suffix++
    }
    return key
}
def projectKey = generateKey(baseName)
log.warn("Generated key: ${projectKey}")

// Create project data
def creationData = new ProjectCreationData.Builder()
        .withName(uniqueName)
        .withKey(projectKey)
        .withDescription("Created from ${issue.key}")
        .withLead(lead)
        .withAssigneeType(AssigneeTypes.PROJECT_LEAD)
        .withType(projectTypeKey)
        .build()

def validationResult = projectService.validateCreateProject(loggedInUser, creationData)
if (!validationResult.valid) {
    log.error("Validation failed: ${validationResult.errorCollection}")
    throw new Exception("Project validation failed")
}

projectService.createProject(validationResult)
log.warn("Project created with key ${projectKey}")

// Retry fetch with cache refresh for multi-node
def project = null
int retries = 20  // Increased for prod clustering
while (retries > 0 && !project) {
    projectManager.refreshAllProjects()  // Force cache reload from DB
    project = projectManager.getProjectByCurrentKey(projectKey)
    if (!project) {
        log.warn("Project not found yet on node ${nodeId}, retrying (${retries} left)")
        Thread.sleep(5000)  // 5s for replication delay
        retries--
    }
}
if (!project) {
    log.error("Failed to fetch project after retries")
    throw new Exception("Project fetch failed after retries—likely caching issue")
}
log.warn("Fetched project ID: ${project.id}")

// Create board if software
if (typeKeyStr == "software" && boardType) {
    def projectIds = [project.id as String] as Set
    def boardName = "${uniqueName} board"
    def boardOutcome = rapidViewHelper.createRapidViewForPreset(loggedInUser, boardName, projectIds, boardType)
    if (!boardOutcome.valid) {
        log.error("Board creation failed: ${boardOutcome.errors}")
        throw new Exception("Board creation failed")
    }
    log.warn("Created ${boardType} board: ${boardName}")
}

// Add comment to the issue with project link
def baseUrl = applicationProperties.getString("jira.baseurl")
def projectLink = "${baseUrl}/projects/${projectKey}"
def commentBody = "New project created: [${uniqueName}|${projectLink}]"
commentManager.create(issue, loggedInUser, commentBody, false) // Set to true if you want to dispatch event/notify watchers
log.warn("Added comment to ${issue.key} with project link")

log.warn("Script completed successfully on node ${nodeId}")