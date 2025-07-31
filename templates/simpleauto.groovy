import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.bc.project.ProjectCreationData
import com.atlassian.jira.bc.project.ProjectService
import com.atlassian.jira.project.AssigneeTypes
import com.atlassian.jira.project.type.ProjectTypeKey
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.issue.Issue
import org.apache.log4j.Level
import org.apache.log4j.Logger

def log = Logger.getLogger("createProjectAutomation")
log.setLevel(Level.WARN)

// Get the issue that triggered the automation
def issue = issue as Issue // Provided by ScriptRunner automation binding
if (!issue) {
    log.error("No issue context found")
    return
}

// Initialize components
def projectService = ComponentAccessor.getComponent(ProjectService)
def projectManager = ComponentAccessor.projectManager
def userManager = ComponentAccessor.userManager
def cfManager = ComponentAccessor.customFieldManager
def loggedInUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser // Automation user

// Get custom field values
def nameCf = cfManager.getCustomFieldObject("customfield_10100") // Project Name
def projectName = issue.getCustomFieldValue(nameCf) as String
log.warn("Project name: ${projectName}")

def typeCf = cfManager.getCustomFieldObject("customfield_10101") // Project Type
def projectTypeStr = issue.getCustomFieldValue(typeCf) as String
log.warn("Project type: ${projectTypeStr}")

def leadCf = cfManager.getCustomFieldObject("customfield_10103") // Project Lead
def lead = issue.getCustomFieldValue(leadCf) as ApplicationUser
log.warn("Project lead: ${lead?.username}")

// Validate required fields
if (!projectName || !projectTypeStr || !lead) {
    log.error("Missing required fields: projectName=${projectName}, projectType=${projectTypeStr}, projectLead=${lead?.username}")
    return
}

// Map project type
def typeKeyStr
switch (projectTypeStr.toLowerCase()) {
    case ~/.*software.*/:
        typeKeyStr = "software"
        break
    case ~/.*business.*/:
        typeKeyStr = "business"
        break
    case ~/.*service.*/:
        typeKeyStr = "service_desk"
        break
    default:
        log.error("Unsupported project type: ${projectTypeStr}")
        return
}
def projectTypeKey = new ProjectTypeKey(typeKeyStr)

// Generate unique project name
def uniqueName = projectName
int nameSuffix = 1
while (projectManager.getProjectObjByName(uniqueName)) {
    uniqueName = "${projectName} (${nameSuffix})"
    nameSuffix++
}
log.warn("Unique project name: ${uniqueName}")

// Generate unique project key
def generateKey = { String name ->
    def cleanedName = name.replaceAll("[^A-Za-z]", "").trim().toUpperCase()
    if (!cleanedName || cleanedName.length() < 2) {
        log.error("Invalid project name for key generation")
        return null
    }
    def baseKey = cleanedName[0..Math.min(4, cleanedName.length() - 1)]
    def key = baseKey
    int suffix = 1
    while (projectManager.getProjectByCurrentKey(key)) {
        key = baseKey + suffix
        suffix++
    }
    return key
}
def projectKey = generateKey(projectName)
if (!projectKey) {
    log.error("Cannot generate valid project key")
    return
}
log.warn("Generated project key: ${projectKey}")

// Create project data
def creationData = new ProjectCreationData.Builder()
        .withName(uniqueName)
        .withKey(projectKey)
        .withLead(lead)
        .withType(projectTypeKey)
        .withAssigneeType(AssigneeTypes.PROJECT_LEAD)
        .build()

// Validate and create project
def validationResult = projectService.validateCreateProject(loggedInUser, creationData)
if (!validationResult.valid) {
    log.error("Validation failed: ${validationResult.errorCollection}")
    return
}

// Create the project
projectService.createProject(validationResult)
log.warn("Project created with key ${projectKey}")

// Handle cache for multi-node compatibility
def project = null
int retries = 10
while (retries > 0 && !project) {
    projectManager.refresh()
    project = projectManager.getProjectByCurrentKey(projectKey)
    if (!project) {
        log.warn("Project not found yet, retrying (${retries} left)")
        Thread.sleep(2000)
        retries--
    }
}
if (!project) {
    log.error("Failed to fetch project after retries")
    return
}
log.warn("Project fetched: ${project.key}")
