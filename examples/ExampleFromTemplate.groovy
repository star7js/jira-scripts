package com.newgrok

import com.atlassian.jira.bc.project.ProjectCreationData
import com.atlassian.jira.bc.project.ProjectService
import com.atlassian.jira.bc.projectroles.ProjectRoleService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.AssigneeTypes
import com.atlassian.jira.project.type.ProjectTypeKey
import com.atlassian.jira.security.roles.ProjectRoleManager
import com.atlassian.jira.security.roles.ProjectRoleActor
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.cluster.ClusterManager
import com.atlassian.jira.util.SimpleErrorCollection
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
def projectRoleService = ComponentAccessor.getComponent(ProjectRoleService)
def projectRoleManager = ComponentAccessor.getComponent(ProjectRoleManager)
def userManager = ComponentAccessor.userManager
def clusterManager = ComponentAccessor.getComponent(ClusterManager)
def loggedInUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser

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

// Retry fetch due to potential cache delay in multi-node
def project = null
int retries = 10
while (retries > 0 && !project) {
    project = projectManager.getProjectByCurrentKey(projectKey)
    if (!project) {
        log.warn("Project not found yet on node ${nodeId}, retrying (${retries} left)")
        Thread.sleep(2000)
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

// Add users to roles
def adminsRole = projectRoleManager.getProjectRole("Administrators")
if (!adminsRole) {
    log.error("Administrators role not found")
    throw new Exception("Role missing")
}

// Add lead and additional admins
def adminActors = [lead.key] as Set<String>
adminActors.addAll(adminUsers.collect { it.key })

// Add JIP bot if yes (replace username if not exact)
if (jipYes) {
    def jipBot = userManager.getUserByName("jip-user-bot") // Placeholder—confirm exact name
    if (jipBot) {
        adminActors.add(jipBot.key)
        log.warn("Adding JIP bot: ${jipBot.username}")
    } else {
        log.warn("JIP bot user not found—skipping")
    }
}

def errorCollection = new SimpleErrorCollection()
projectRoleService.addActorsToProjectRole(adminActors, adminsRole, project, ProjectRoleActor.USER_ROLE_ACTOR_TYPE, errorCollection)
if (errorCollection.hasAnyErrors()) {
    log.error("Errors adding admins: ${errorCollection.errors}")
    throw new Exception("Admin role addition failed")
}
log.warn("Added admins: ${adminActors}")

// Add service desk users if any
if (sdUsers) {
    def sdRole = projectRoleManager.getProjectRole("Service Desk Team")
    if (sdRole) {
        def sdActors = sdUsers.collect { it.key } as Set<String>
        def sdErrorCollection = new SimpleErrorCollection()
        projectRoleService.addActorsToProjectRole(sdActors, sdRole, project, ProjectRoleActor.USER_ROLE_ACTOR_TYPE, sdErrorCollection)
        if (sdErrorCollection.hasAnyErrors()) {
            log.error("Errors adding service desk users: ${sdErrorCollection.errors}")
            throw new Exception("Service desk role addition failed")
        }
        log.warn("Added service desk users: ${sdActors}")
    } else {
        log.warn("Service Desk Team role not found—skipping")
    }
}

log.warn("Script completed successfully on node ${nodeId}")
