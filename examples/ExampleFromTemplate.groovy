import com.atlassian.jira.bc.projectroles.ProjectRoleService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.security.roles.ProjectRole
import com.atlassian.jira.security.roles.ProjectRoleManager
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.cluster.ClusterManager
import com.atlassian.jira.project.AssigneeTypes
import com.atlassian.jira.util.ErrorCollection
import com.atlassian.jira.util.SimpleErrorCollection
import com.onresolve.scriptrunner.canned.jira.admin.CopyProject
import org.apache.log4j.Logger
import org.apache.log4j.Level

def log = Logger.getLogger("com.onresolve.scriptrunner.runner.ScriptRunnerImpl")
log.setLevel(Level.DEBUG)

// Hardcoded configurations - replace with your values
def String adminUsername = "admin" // Your automation/service account with admin rights
def long openPermissionSchemeId = 10001 // ID of your open permission scheme (if not handled by listener)
def String actorTypeUser = "atlassian-user-role-actor" // For adding users to roles

// Template mapping (keys of your manually created template projects)
def templateMap = [
    "Software - Kanban (Most Popular Choice)": "KANBANT",
    "Software - Scrum (Good For Sprints)": "SCRUMT",
    "Business - Project Management": "BUSPMT",
    "Business - Process Management": "BUSPROCT",
    "Business - Task Management": "BUSTASKT",
    "Service - Jira Service Management (Customer Portal)": "SERVICET"
]

// Get components
def customFieldManager = ComponentAccessor.getCustomFieldManager()
def projectManager = ComponentAccessor.getProjectManager()
def projectRoleManager = ComponentAccessor.getComponent(ProjectRoleManager.class)
def projectRoleService = ComponentAccessor.getComponent(ProjectRoleService.class)
def permissionSchemeManager = ComponentAccessor.getPermissionSchemeManager()
def userManager = ComponentAccessor.getUserManager()
def authenticationContext = ComponentAccessor.getJiraAuthenticationContext()
def clusterManager = ComponentAccessor.getComponent(ClusterManager.class)

// Log current node for debugging
def nodeId = clusterManager?.getNodeId() ?: "unknown"
log.debug("Running on node: ${nodeId}")

// Get the automation user
def ApplicationUser adminUser = userManager.getUserByName(adminUsername)
if (!adminUser) {
    log.error("Automation user not found: ${adminUsername}")
    return "Error: Automation user not found."
}

// Backup current user
def ApplicationUser originalUser = authenticationContext.getLoggedInUser()

// Custom fields - use getCustomFieldObjectsByName and take first (handles deprecation)
def projectNameField = customFieldManager.getCustomFieldObjectsByName("New Project Or Team")?.first()
def projectTypeField = customFieldManager.getCustomFieldObjectsByName("Project Type")?.first()
def projectCategoryField = customFieldManager.getCustomFieldObjectsByName("Project Category")?.first()
def projectLeadField = customFieldManager.getCustomFieldObjectsByName("Project Lead / Project Admin")?.first()
def adminUsersField = customFieldManager.getCustomFieldObjectsByName("List All Users That Should Have Administrator Rights On The Project")?.first()
def serviceDeskUsersField = customFieldManager.getCustomFieldObjectsByName("List All Users That Should Have Service Desk Team Rights On The Project")?.first()
def viewAllField = customFieldManager.getCustomFieldObjectsByName("Should All Employees Have The Ability To View This Project?")?.first()
def createEditAllField = customFieldManager.getCustomFieldObjectsByName("Should All Employees Have The Ability To Create/Edit Issues In This Project?")?.first()

// Extract values
def String projectName = issue.getCustomFieldValue(projectNameField) as String
if (!projectName) return "Error: Project name required."

def String selectedType = issue.getCustomFieldValue(projectTypeField) as String
if (!selectedType) return "Error: Project type required."
def String templateProjectKey = templateMap[selectedType]
if (!templateProjectKey) return "Error: No template for '${selectedType}'."

// Generate unique key
def String baseKey = projectName.split("\\s+").collect { it[0]?.toUpperCase() ?: "" }.join("")
if (baseKey.length() < 2) return "Error: Invalid key from name."
def String projectKey = baseKey
def int suffix = 1
while (projectManager.getProjectObjByKey(projectKey)) {
    projectKey = baseKey + suffix++
}

def ApplicationUser projectLead = issue.getCustomFieldValue(projectLeadField) as ApplicationUser ?: issue.getReporter()

def List<ApplicationUser> adminUsers = issue.getCustomFieldValue(adminUsersField) as List<ApplicationUser> ?: []
def List<ApplicationUser> serviceDeskUsers = issue.getCustomFieldValue(serviceDeskUsersField) as List<ApplicationUser> ?: []

def String viewAll = issue.getCustomFieldValue(viewAllField)?.toString()
def String createEditAll = issue.getCustomFieldValue(createEditAllField)?.toString()

def String projectDescription = issue.getDescription() ?: "Created from issue ${issue.key}"

def String categoryName = issue.getCustomFieldValue(projectCategoryField) as String
def category = categoryName ? projectManager.getProjectCategoryObjectByName(categoryName) : null
if (categoryName && !category) log.warn("Category not found: ${categoryName}")

// Prepare copy inputs
def inputs = [
    (CopyProject.FIELD_SOURCE_PROJECT) : templateProjectKey,
    (CopyProject.FIELD_TARGET_PROJECT) : projectKey,
    (CopyProject.FIELD_TARGET_PROJECT_NAME) : projectName,
    (CopyProject.FIELD_COPY_VERSIONS) : false,
    (CopyProject.FIELD_COPY_COMPONENTS) : false,
    (CopyProject.FIELD_COPY_ISSUES) : false,
    (CopyProject.FIELD_COPY_DASH_AND_FILTERS) : false
]

def copyProject = new CopyProject()
def errorCollection = copyProject.doValidate(inputs, false)
if (errorCollection.hasAnyErrors()) {
    return "Validation error: ${errorCollection.errors + errorCollection.errorMessages}"
}

// Execute copy as automation user
try {
    authenticationContext.setLoggedInUser(adminUser)
    copyProject.doScript(inputs)
} finally {
    authenticationContext.setLoggedInUser(originalUser)
}

def Project newProject = projectManager.getProjectObjByKey(projectKey)
if (!newProject) {
    // Poll for visibility (up to 10s)
    def maxPoll = 10
    def pollCount = 0
    while (!newProject && pollCount < maxPoll) {
        Thread.sleep(1000)
        newProject = projectManager.getProjectObjByKey(projectKey)
        pollCount++
        log.debug("Polling for project on node ${nodeId}: attempt ${pollCount}")
    }
    if (!newProject) return "Error: Project creation failed after polling."
}

// Update details
projectManager.updateProject(newProject, projectName, projectDescription, projectLead.getKey(), newProject.getUrl(), AssigneeTypes.PROJECT_LEAD, newProject.getAvatar())

// Set category
if (category) {
    projectManager.setProjectCategory(newProject, category)
}

// Apply open scheme if needed
def boolean useOpenScheme = (createEditAll == "Yes" || viewAll == "Yes")
if (useOpenScheme) {
    def scheme = permissionSchemeManager.getSchemeObject(openPermissionSchemeId)
    if (scheme) permissionSchemeManager.assignPermissionScheme(newProject, scheme)
}

// Add users to roles using non-deprecated ProjectRoleService
def ErrorCollection errors = new SimpleErrorCollection()

def ProjectRole adminRole = projectRoleManager.getProjectRole("Administrators")
if (adminRole && adminUsers) {
    projectRoleService.addActorsToProjectRole(adminUsers.collect { it.key }, adminRole, newProject, actorTypeUser, errors)
    if (errors.hasAnyErrors()) log.error("Error adding admins: ${errors}")
}

def ProjectRole serviceDeskRole = projectRoleManager.getProjectRole("Service Desk Team")
if (serviceDeskRole && serviceDeskUsers) {
    projectRoleService.addActorsToProjectRole(serviceDeskUsers.collect { it.key }, serviceDeskRole, newProject, actorTypeUser, errors)
    if (errors.hasAnyErrors()) log.error("Error adding service desk users: ${errors}")
}

if (adminRole && projectLead) {
    projectRoleService.addActorsToProjectRole([projectLead.key], adminRole, newProject, actorTypeUser, errors)
    if (errors.hasAnyErrors()) log.error("Error adding lead to admins: ${errors}")
}

return "Project created: ${projectKey} - ${projectName} on node ${nodeId}. Adjustments applied by listener."