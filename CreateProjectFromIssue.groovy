package com.company.jira.scripts

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.project.AssigneeTypes
import com.atlassian.jira.security.roles.ProjectRoleManager
import com.atlassian.jira.security.roles.ProjectRoleActor
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.bc.project.ProjectCreationData
import com.atlassian.jira.bc.project.ProjectService
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.fields.option.Option
import com.atlassian.jira.issue.comments.CommentManager
import com.atlassian.jira.permission.PermissionSchemeManager
import com.atlassian.cache.CacheManager
import com.atlassian.cache.Cache
import com.atlassian.cache.CacheSettingsBuilder
import com.atlassian.beehive.ClusterLockService
import org.apache.log4j.Logger
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.Random

/**
 * Jira Data Center compatible project creation script
 * Handles multi-node environments with proper locking and caching
 * Fixed version with improved null handling and validation
 */
class ProjectCreationScript {
    
    private static final Logger log = Logger.getLogger(ProjectCreationScript.class)
    private static final String CACHE_NAME = "project-creation-cache"
    private static final String LOCK_PREFIX = "project-creation-lock-"
    private static final int LOCK_TIMEOUT_SECONDS = 30
    private static final int MAX_RETRIES = 2  // Reduced to minimize blocking
    
    // Project configuration constants
    private static final String PROJECT_TYPE = "Project Type"
    private static final String PROJECT_LEAD = "Project Lead / Project Admin"
    private static final String ADMIN_USERS = "List All Users That Should Have Administrator Rights On The Project?"
    private static final String SERVICE_DESK_USERS = "List All Users That Should Have Service Desk Team Rights On The Project?"
    private static final String ALL_EMPLOYEES_VIEW = "Should All Employees Have The Ability To View This Project?"
    private static final String ALL_EMPLOYEES_EDIT = "Should All Employees Have The Ability To Create/Edit Issues In This Project?"
    private static final String USE_WITH_JIP = "Will This Project Be Used With JIP Or Jira Integration Plus"
    private static final String OPSEC_PROGRAM = "OPSEC Program to onboard to"
    private static final String NEW_PROJECT_NAME_FIELD = "New Project Or Team Space Name"
    private static final String ALL_EMPLOYEES_GROUP = "jira-users" // Default group for all employees

    // Role Names
    private static final String ROLE_ADMINISTRATORS = "Administrators"
    private static final String ROLE_SERVICE_DESK_TEAM = "Service Desk Team"
    private static final String ROLE_USERS = "Users"
    private static final String ROLE_DEVELOPERS = "Developers"
    
    private static final Map<String, String> PROJECT_TYPE_MAPPING = [
        "Software - Scrum - (Most Popular Choice)": "software",
        "Software - Scrum - (Good For Sprints)": "software",
        "Business - Project Management": "business",
        "Business - Task Management": "business",
        "Service Management - IT": "service_desk",
        "Service Management - General": "service_desk"
    ]
    
    // Services - will be initialized once
    private ProjectService projectService
    private ProjectManager projectManager
    private ProjectRoleManager projectRoleManager
    private CustomFieldManager customFieldManager
    private CacheManager cacheManager
    private ClusterLockService clusterLockService
    private CommentManager commentManager
    private PermissionSchemeManager permissionSchemeManager
    
    ProjectCreationScript() {
        initializeServices()
    }
    
    /**
     * Validate required custom fields exist before processing
     */
    private Map validateCustomFields() {
        def requiredFields = [NEW_PROJECT_NAME_FIELD, PROJECT_TYPE, PROJECT_LEAD]
        def missingFields = []
        def fieldDetails = [:]

        requiredFields.each { fieldName ->
            def field = customFieldManager.getCustomFieldObjectsByName(fieldName)
            if (!field) {
                missingFields.add(fieldName)
            } else {
                fieldDetails[fieldName] = field
                log.warn("Found custom field: ${fieldName} (ID: ${field.id})")
            }
        }

        if (missingFields) {
            log.error("Missing required custom fields: ${missingFields}")
            return [success: false, error: "Missing custom fields: ${missingFields.join(', ')}", fields: fieldDetails]
        }
        
        return [success: true, fields: fieldDetails]
    }
    
    /**
     * Copy and assign permission scheme to project
     * This is done within the same lock to prevent race conditions
     */
    private Map copyAndAssignPermissionScheme(Issue issue, Project project, Map projectDetails) {
        try {
            def permissionsScript = new CopyPermissionsScheme()
            
            def allEmployeesView = projectDetails.allEmployeesView == "yes"
            def allEmployeesEdit = projectDetails.allEmployeesEdit == "yes"
            def isOpsec = projectDetails.opsecProgram != null && projectDetails.opsecProgram.trim() != ""
            
            // Copy the appropriate permission scheme
            def schemeResult = permissionsScript.execute(
                project.name,
                allEmployeesEdit,
                allEmployeesView,
                isOpsec
            )
            
            if (schemeResult.success) {
                // Assign the new scheme to the project
                def assignResult = permissionsScript.assignSchemeToProject(
                    project.key,
                    schemeResult.scheme.name
                )
                
                if (!assignResult.success) {
                    log.error("Failed to assign permission scheme: ${assignResult.error}")
                    return [success: false, error: assignResult.error]
                }
                
                log.warn("Successfully assigned permission scheme to project ${project.key}")
                return [success: true]
            } else {
                log.error("Failed to copy permission scheme: ${schemeResult.error}")
                return [success: false, error: schemeResult.error]
            }
        } catch (Exception e) {
            log.error("Error setting up permission scheme", e)
            return [success: false, error: "Permission scheme error: ${e.message}"]
        }
    }
    
    private void initializeServices() {
        this.projectService = ComponentAccessor.getComponent(ProjectService.class)
        this.projectManager = ComponentAccessor.getProjectManager()
        this.projectRoleManager = ComponentAccessor.getComponent(ProjectRoleManager.class)
        this.customFieldManager = ComponentAccessor.getCustomFieldManager()
        this.cacheManager = ComponentAccessor.getComponent(CacheManager.class)
        this.clusterLockService = ComponentAccessor.getComponent(ClusterLockService.class)
        this.commentManager = ComponentAccessor.getCommentManager()
        this.permissionSchemeManager = ComponentAccessor.getPermissionSchemeManager()
    }
    
    /**
     * Main entry point for creating a project from an issue
     */
    def createProjectFromIssue(Issue issue) {
        log.warn("Starting project creation from issue: ${issue?.key}")
        
        try {
            // Validate issue first
            if (!issue) {
                log.error("Issue is null")
                return [success: false, error: "Issue is null"]
            }
            
            // Validate custom fields exist
            def fieldValidation = validateCustomFields()
            if (!fieldValidation.success) {
                addCommentToIssue(issue, "Error: ${fieldValidation.error}")
                return fieldValidation
            }
            
            // Extract project details from issue
            def projectDetails = extractProjectDetails(issue)
            log.warn("Extracted project details: ${projectDetails}")
            
            if (!projectDetails) {
                log.error("Failed to extract project details from issue ${issue.key}")
                addCommentToIssue(issue, "Error: Failed to extract project details. Check that all required custom fields are filled.")
                return [success: false, error: "Failed to extract project details"]
            }
            
            // CRITICAL: Validate project key was generated successfully
            if (!projectDetails.projectKey || projectDetails.projectKey.trim().isEmpty()) {
                log.error("Failed to generate valid project key for issue ${issue.key}")
                addCommentToIssue(issue, "Error: Could not generate project key. Check custom fields and issue summary.")
                return [success: false, error: "Project key generation failed"]
            }
            
            // Validate project doesn't already exist
            if (projectExists(projectDetails.projectKey, projectDetails.projectName)) {
                log.warn("Project with key ${projectDetails.projectKey} already exists")
                addCommentToIssue(issue, "Error: Project with key ${projectDetails.projectKey} already exists")
                return [success: false, error: "Project with key ${projectDetails.projectKey} already exists"]
            }
            
            // Acquire distributed lock for project creation using ClusterLockService
            def lockKey = LOCK_PREFIX + projectDetails.projectKey
            def lock = clusterLockService.getLockForName(lockKey)
            
            if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.error("Failed to acquire cluster lock for project creation: ${projectDetails.projectKey}")
                addCommentToIssue(issue, "Failed to create project: Another process is currently creating this project. Please try again.")
                return [success: false, error: "Another process is creating this project"]
            }
            
            // Log which node acquired the lock
            def nodeId = ComponentAccessor.getComponent(com.atlassian.jira.cluster.ClusterManager.class)?.getNodeId() ?: "single-node"
            log.warn("Acquired cluster lock: ${lockKey} on node ${nodeId}")
            
            try {
                // Double-check project doesn't exist after acquiring lock
                if (projectExists(projectDetails.projectKey, projectDetails.projectName)) {
                    log.warn("Project with key ${projectDetails.projectKey} was created by another node")
                    addCommentToIssue(issue, "Project with key ${projectDetails.projectKey} was created by another process")
                    return [success: false, error: "Project was created by another process"]
                }
                
                // Create project with retry logic
                def result = createProjectWithRetry(projectDetails)
                
                if (result.success) {
                    // Clear cache after successful creation
                    clearProjectCache(projectDetails.projectKey)
                    log.warn("Successfully created project: ${projectDetails.projectKey}")
                    
                    // Copy and assign permissions scheme while still holding the lock
                    def permissionResult = copyAndAssignPermissionScheme(issue, result.project, projectDetails)
                    if (permissionResult.success) {
                        addCommentToIssue(issue, "Successfully created project: ${projectDetails.projectKey} with permissions configured")
                    } else {
                        addCommentToIssue(issue, "Project created: ${projectDetails.projectKey}, but permission scheme assignment failed: ${permissionResult.error}")
                        // Update the result to indicate partial success
                        result.permissionError = permissionResult.error
                    }
                } else {
                    addCommentToIssue(issue, "Failed to create project: ${result.error}")
                }
                
                return result
                
            } finally {
                lock.unlock()
            }
            
        } catch (Exception e) {
            log.error("Error creating project from issue ${issue?.key}", e)
            addCommentToIssue(issue, "Failed to create project: ${e.message}")
            return [success: false, error: "Unexpected error: ${e.message}"]
        }
    }
    
    /**
     * Extract project details from issue custom fields
     * Improved with better null handling and validation
     */
    private Map extractProjectDetails(Issue issue) {
        try {
            def details = [:]

            if (!issue) {
                log.error("Issue is null")
                return null
            }

            // Get the value from the "New Project Or Team Space Name" field to be used for both the project key and name.
            def newProjectNameValue = getCustomFieldValue(issue, NEW_PROJECT_NAME_FIELD)
            log.warn("Value from '${NEW_PROJECT_NAME_FIELD}': '${newProjectNameValue}'")

            // Determine the base project name: Use the custom field, or fall back to the issue summary.
            def baseProjectName = newProjectNameValue?.trim() ?: issue.summary?.trim()
            
            if (!baseProjectName) {
                log.error("Could not determine a project name from custom field ('${NEW_PROJECT_NAME_FIELD}') or issue summary.")
                return null
            }
            
            // The project KEY is generated preferentially from the custom field value.
            details.projectKey = generateProjectKey(issue, newProjectNameValue)
            
            // Generate a UNIQUE project name by appending the key to the base name.
            def sanitizedProjectName = baseProjectName.replaceAll(/[<>:"\/\\|?*]/, "")
            def uniqueProjectName = "${sanitizedProjectName} (${details.projectKey})"
            
            // Enforce Jira's 80-character limit for project names.
            if (uniqueProjectName.length() > 80) {
                def truncateLength = 80 - " (${details.projectKey})".length()
                sanitizedProjectName = sanitizedProjectName.take(truncateLength)
                uniqueProjectName = "${sanitizedProjectName} (${details.projectKey})"
            }
            details.projectName = uniqueProjectName

            // Get all other custom field values.
            details.projectType = getCustomFieldValue(issue, PROJECT_TYPE)
            details.projectLead = getCustomFieldValue(issue, PROJECT_LEAD)
            details.adminUsers = getCustomFieldValue(issue, ADMIN_USERS)
            details.serviceDeskUsers = getCustomFieldValue(issue, SERVICE_DESK_USERS)
            details.allEmployeesView = getCustomFieldValue(issue, ALL_EMPLOYEES_VIEW)
            details.allEmployeesEdit = getCustomFieldValue(issue, ALL_EMPLOYEES_EDIT)
            details.useWithJIP = getCustomFieldValue(issue, USE_WITH_JIP)
            details.opsecProgram = getCustomFieldValue(issue, OPSEC_PROGRAM)
            
            // Log all extracted values for debugging
            log.warn("Extracted values - Name: '${details.projectName}', Key: '${details.projectKey}', Type: '${details.projectType}', Lead: '${details.projectLead}'")
            
            // Validate required fields
            if (!details.projectName || !details.projectKey || !details.projectType || !details.projectLead) {
                log.error("Missing required fields for project creation - Name: ${details.projectName}, Key: ${details.projectKey}, Type: ${details.projectType}, Lead: ${details.projectLead}")
                return null
            }
            
            return details
            
        } catch (Exception e) {
            log.error("Error extracting project details", e)
            return null
        }
    }
    
    /**
     * Create project with retry logic for handling transient failures
     */
    private Map createProjectWithRetry(Map projectDetails) {
        def attempts = 0
        def lastError = null
        
        while (attempts < MAX_RETRIES) {
            attempts++
            
            try {
                // Create the project directly without a transaction template
                return createProject(projectDetails)
                
            } catch (Exception e) {
                lastError = e
                log.warn("Project creation attempt ${attempts} failed: ${e.message}")
                
                if (attempts < MAX_RETRIES) {
                    // Use exponential backoff with jitter instead of simple sleep
                    def backoffTime = (long)(Math.pow(2, attempts) * 1000 * (0.5 + Math.random() * 0.5))
                    Thread.sleep(backoffTime)
                }
            }
        }
        
        log.error("Failed to create project after ${MAX_RETRIES} attempts", lastError)
        return [success: false, error: "Failed after ${MAX_RETRIES} attempts: ${lastError?.message}"]
    }
    
    /**
     * Create the project and configure permissions
     */
    private Map createProject(Map details) {
        log.warn("Creating project with key: ${details.projectKey}")
        
        // Get the current user
        def currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
        
        // Get the project lead user object from the username string
        def userManager = ComponentAccessor.getUserManager()
        def leadUser = userManager.getUserByName(details.projectLead)

        if (!leadUser) {
            log.error("Project Lead user '${details.projectLead}' not found.")
            return [success: false, error: "Project Lead user '${details.projectLead}' not found."]
        }
        
        // Create project creation data
        def builder = new ProjectCreationData.Builder()
            .withName(details.projectName)
            .withKey(details.projectKey)
            .withDescription(details.projectName?.take(255)) // Limit description length
            .withLead(leadUser)
            .withAssigneeType(AssigneeTypes.PROJECT_LEAD)
        
        // Set project type
        def projectTypeKey = PROJECT_TYPE_MAPPING[details.projectType] ?: "business"
        builder.withType(projectTypeKey)
        
        def projectCreationData = builder.build()
        
        // Validate and create project
        def validationResult = projectService.validateCreateProject(currentUser, projectCreationData)
        
        if (!validationResult.valid) {
            def errors = validationResult.errorCollection.errors.collect { "${it.key}: ${it.value}" }.join(", ")
            log.error("Project validation failed: ${errors}")
            return [success: false, error: "Validation failed: ${errors}"]
        }
        
        def projectResult = projectService.createProject(validationResult)
        
        // Handle different possible result structures for Jira 9.12
        def isValid = false
        def project = null
        def errors = []
        
        try {
            // Updated logic to handle the project creation result more reliably.
            // On success, 'createProject' returns a Project object. On failure, it returns a result object with errors.
            if (projectResult instanceof Project) {
                isValid = true
                project = projectResult
            } else if (projectResult.hasProperty('isValid') && !projectResult.isValid()) {
                isValid = false
                if (projectResult.hasProperty('errorCollection')) {
                    errors = projectResult.errorCollection.errors.collect { "${it.key}: ${it.value}" }
                }
            } else if (projectResult.hasProperty('errorCollection') && projectResult.errorCollection.hasErrors()) {
                isValid = false
                errors = projectResult.errorCollection.errors.collect { "${it.key}: ${it.value}" }
            } else if (projectResult.hasProperty('project') && projectResult.project != null) {
                // Fallback for other result wrapper types
                isValid = true
                project = projectResult.project
            }
            
        } catch (Exception e) {
            log.error("Error checking project creation result", e)
            return [success: false, error: "Error checking project creation result: ${e.message}"]
        }
        
        if (!isValid || !project) {
            def errorMsg = errors ? errors.join(", ") : "Project creation failed"
            log.error("Project creation failed: ${errorMsg}")
            return [success: false, error: "Creation failed: ${errorMsg}"]
        }
        
        // Configure project permissions
        configureProjectPermissions(project, details)
        
        return [success: true, project: project]
    }
    
    /**
     * Configure project permissions based on custom field values
     */
    private void configureProjectPermissions(Project project, Map details) {
        log.warn("Configuring permissions for project: ${project.key}")
        
        try {
            // Add administrators
            if (details.adminUsers) {
                addUsersToRole(project, ROLE_ADMINISTRATORS, parseUserList(details.adminUsers))
            }
            
            // Add service desk users
            if (details.serviceDeskUsers) {
                addUsersToRole(project, ROLE_SERVICE_DESK_TEAM, parseUserList(details.serviceDeskUsers))
            }
            
            // Configure all employees permissions
            if (details.allEmployeesView == "yes") {
                addGroupToRole(project, ALL_EMPLOYEES_GROUP, ROLE_USERS)
            }
            
            if (details.allEmployeesEdit == "yes") {
                addGroupToRole(project, ALL_EMPLOYEES_GROUP, ROLE_DEVELOPERS)
            }
            
        } catch (Exception e) {
            log.error("Error configuring project permissions", e)
        }
    }
    
    /**
     * Add users to a project role
     */
    private void addUsersToRole(Project project, String roleName, List<String> usernames) {
        def role = projectRoleManager.getProjectRole(roleName)
        if (!role) {
            log.warn("Role not found: ${roleName}")
            return
        }
        
        def actors = usernames.collect { username ->
            def user = ComponentAccessor.userManager.getUserByName(username)
            if (user) {
                return ProjectRoleActor.USER_ROLE_ACTOR_TYPE + ":" + user.key
            }
            return null
        }.findAll { it != null }
        
        if (actors) {
            projectRoleManager.updateProjectRoleActors(role, project, actors as Set, null)
        }
    }
    
    /**
     * Add a group to a project role
     */
    private void addGroupToRole(Project project, String groupName, String roleName) {
        def role = projectRoleManager.getProjectRole(roleName)
        if (!role) {
            log.warn("Role not found: ${roleName}")
            return
        }
        
        def actors = [ProjectRoleActor.GROUP_ROLE_ACTOR_TYPE + ":" + groupName] as Set
        projectRoleManager.updateProjectRoleActors(role, project, actors, null)
    }
    
    /**
     * Enhanced helper method to get custom field values
     * Handles Options, multi-selects, and various field types
     * Improved with better null handling and logging
     */
    private String getCustomFieldValue(Issue issue, String fieldName) {
        try {
            def customField = customFieldManager.getCustomFieldObjectByName(fieldName)
            if (!customField) {
                log.error("Critical: Custom field '${fieldName}' not found in Jira configuration")
                return null
            }
            
            def value = issue.getCustomFieldValue(customField)
            if (value == null) {
                log.warn("Custom field '${fieldName}' has null value for issue ${issue.key}")
                return null
            }
            
            // Handle different field types
            if (value instanceof Option) {
                return value.getValue()
            } else if (value instanceof List) {
                // Handle multi-select fields
                return value.collect { item ->
                    if (item instanceof Option) {
                        return item.getValue()
                    } else if (item instanceof ApplicationUser) {
                        return item.getUsername()
                    } else {
                        return item.toString()
                    }
                }.join(",")
            } else if (value instanceof ApplicationUser) {
                return value.getUsername()
            } else {
                return value.toString()?.trim()
            }
        } catch (Exception e) {
            log.error("Error getting custom field value for '${fieldName}'", e)
            return null
        }
    }
    
    /**
     * Generate a robust project key with improved null handling and validation
     * 1. From initials of "New Project Or Team Space Name" custom field.
     * 2. Fallback to initials of the issue summary.
     * 3. Fallback to letters from the issue key.
     * Ensures the key is unique, uppercase, 2-10 chars, and starts with a letter.
     */
    private String generateProjectKey(Issue issue, String newProjectName) {
        String baseKey = ""

        try {
            // 1. Try to get initials from the custom field with better null handling
            if (newProjectName?.trim()) {
                def words = newProjectName.trim().split(/\s+/).findAll { it?.trim() }
                if (words.size() > 0) {
                    baseKey = words.collect { word -> 
                        word.trim() ? word[0].toUpperCase() : ""
                    }.findAll { it }.join("").replaceAll("[^A-Z]", "")
                }
                log.warn("Generated base key from custom field: '${baseKey}' from '${newProjectName}'")
            }

            // 2. Fallback to issue summary with better handling
            if (!baseKey || baseKey.length() < 2) {
                def summary = issue.summary?.trim()
                if (summary) {
                    def words = summary.split(/\s+/).findAll { it?.trim() }
                    if (words.size() > 0) {
                        baseKey = words.collect { word -> 
                            word.trim() ? word[0].toUpperCase() : ""
                        }.findAll { it }.join("").replaceAll("[^A-Z]", "")
                    }
                }
                log.warn("Generated base key from summary: '${baseKey}' from '${summary}'")
            }

            // 3. Final fallback - use issue key
            if (!baseKey || baseKey.length() < 2) {
                baseKey = issue.key?.replaceAll("[^A-Z]", "") ?: "PROJ"
                log.warn("Generated base key from issue key: '${baseKey}' from '${issue.key}'")
            }
            
            // Ensure we always have a valid starting point
            if (!baseKey || !baseKey.matches("^[A-Z].*")) {
                baseKey = "PROJ" // Safe default
                log.warn("Using default base key: ${baseKey}")
            }

            baseKey = baseKey.take(4) // Take up to 4 characters for the base

            // Generate a hash of the issue key + timestamp for uniqueness
            def uniqueString = "${issue.key}-${System.currentTimeMillis()}-${Thread.currentThread().getId()}-${Math.random()}"
            def messageDigest = MessageDigest.getInstance("MD5")
            def hashBytes = messageDigest.digest(uniqueString.getBytes())
            def hashString = hashBytes.encodeHex().toString().toUpperCase().replaceAll("[^A-Z0-9]", "")

            // Combine base key with hash suffix (ensure total length 2-10 chars)
            def maxSuffixLength = Math.max(0, 10 - baseKey.length())
            def suffix = hashString.take(maxSuffixLength)
            def projectKey = "${baseKey}${suffix}".take(10)

            // Final validation and fallback to a completely safe key
            if (!projectKey || !projectKey.matches("[A-Z][A-Z0-9]{1,9}")) {
                log.error("Invalid project key generated: '${projectKey}'. Falling back to default.")
                def timestamp = System.currentTimeMillis().toString().takeLast(6)
                def random = Math.abs(new Random().nextInt(999)).toString().padLeft(3, '0')
                projectKey = "PR${timestamp}${random}".take(10)
            }
            
            log.warn("Generated project key: ${projectKey} for issue ${issue.key}")
            return projectKey
            
        } catch (Exception e) {
            log.error("Error generating project key", e)
            // Emergency fallback
            def timestamp = System.currentTimeMillis().toString().takeLast(6)
            def emergencyKey = "ER${timestamp}"
            log.warn("Using emergency project key: ${emergencyKey}")
            return emergencyKey
        }
    }
    
    private boolean projectExists(String projectKey, String projectName) {
        // Validate project key first
        if (!projectKey || projectKey.trim().isEmpty()) {
            log.error("Cannot check if project exists: project key is null or empty")
            return true // Treat as existing to prevent proceeding
        }
        
        try {
            // Check cache first for key
            def cache = getProjectCache()
            if (cache.get(projectKey)) {
                return true
            }
            
            // Check database for key
            if (projectManager.getProjectObjByKey(projectKey)) {
                cache.put(projectKey, true)
                return true
            }
            
            // Check database for name
            if (projectName && projectManager.getProjectObjByName(projectName)) {
                log.warn("Project with name '${projectName}' already exists.")
                return true
            }
            
            return false
        } catch (Exception e) {
            log.error("Error checking if project exists: ${projectKey}", e)
            return true // Fail safe
        }
    }
    
    private List<String> parseUserList(String userList) {
        if (!userList?.trim()) {
            return []
        }
        
        return userList.split("[,;\\s]+")
            .collect { it.trim() }
            .findAll { it }
    }
    
    private Cache<String, Boolean> getProjectCache() {
        return cacheManager.getCache(CACHE_NAME, null, 
            new CacheSettingsBuilder()
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .maxEntries(1000)
                .build())
    }
    
    private void clearProjectCache(String projectKey) {
        try {
            def cache = getProjectCache()
            cache.remove(projectKey)
        } catch (Exception e) {
            log.error("Error clearing project cache", e)
        }
    }
    
    /**
     * Add comment to issue for audit trail
     */
    private void addCommentToIssue(Issue issue, String message) {
        try {
            if (!issue) {
                log.error("Cannot add comment: issue is null")
                return
            }
            
            def currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
            commentManager.create(issue, currentUser, message, false)
            log.warn("Added comment to issue ${issue.key}: ${message}")
        } catch (Exception e) {
            log.error("Failed to add comment to issue", e)
        }
    }
}

/**
 * Permission scheme copier for Jira Data Center
 * Thread-safe implementation for multi-node environments
 */
class CopyPermissionsScheme {
    
    private static final Logger log = Logger.getLogger(CopyPermissionsScheme.class)
    private static final String OPSEC_SCHEME = "app-jira-ironwood-permissions-scheme"
    private static final String VIEW_ONLY_SCHEME = "app-jira-all-employees-view-only-permissions-scheme"
    private static final String EDIT_SCHEME = "app-jira-all-employees-edit-permissions-scheme" // For projects where all employees can edit
    private static final String PRIVATE_SCHEME = "app-jira-private-project-permissions-scheme"
    
    private PermissionSchemeManager permissionSchemeManager
    private ProjectManager projectManager
    
    CopyPermissionsScheme() {
        this.permissionSchemeManager = ComponentAccessor.getPermissionSchemeManager()
        this.projectManager = ComponentAccessor.getProjectManager()
    }
    
    /**
     * Generate a new permissions scheme name based on the project name
     */
    String generateSchemeName(String projectName) {
        if (!projectName?.trim()) {
            return "app-jira-default-permissions-scheme"
        }
        return "app-jira-${projectName.toLowerCase().trim().replaceAll(/\s+/, '-')}-permissions-scheme"
    }
    
    /**
     * Execute permission scheme copy with proper error handling
     */
    def execute(String projectName, boolean allEmployeesCanEdit, boolean allEmployeesCanView, boolean isOpsec) {
        log.warn("Executing CopyPermissionsScheme for project: ${projectName}")
        
        try {
            // Validate parameters
            if (!projectName?.trim()) {
                log.error("Project name is required")
                return [success: false, error: "Project name is required"]
            }
            
            if (allEmployeesCanEdit && !allEmployeesCanView) {
                log.error("Invalid combination: allEmployeesCanEdit is true but allEmployeesCanView is false")
                return [success: false, error: "Invalid permission combination"]
            }
            
            // Determine source scheme
            String sourceSchemeName = determineSourceScheme(isOpsec, allEmployeesCanEdit, allEmployeesCanView)
            String newSchemeName = generateSchemeName(projectName)
            
            log.warn("Source scheme: ${sourceSchemeName}, New scheme: ${newSchemeName}")
            
            // Copy the permission scheme
            def result = copyPermissionScheme(sourceSchemeName, newSchemeName)
            
            if (result.success) {
                log.warn("Successfully created permission scheme: ${newSchemeName}")
            }
            
            return result
            
        } catch (Exception e) {
            log.error("Failed to copy permission scheme", e)
            return [success: false, error: "Unexpected error: ${e.message}"]
        }
    }
    
    /**
     * Determine which source scheme to use based on project settings
     */
    private String determineSourceScheme(boolean isOpsec, boolean allEmployeesCanEdit, boolean allEmployeesCanView) {
        if (isOpsec) {
            return OPSEC_SCHEME
        }
        
        if (allEmployeesCanEdit) {
            return EDIT_SCHEME
        }
        
        if (allEmployeesCanView) {
            return VIEW_ONLY_SCHEME
        }
        
        // Default to the most restrictive scheme if no other condition is met
        return PRIVATE_SCHEME
    }
    
    /**
     * Copy permission scheme with proper error handling
     */
    private Map copyPermissionScheme(String sourceSchemeName, String newSchemeName) {
        try {
            // Get source scheme
            def sourceScheme = permissionSchemeManager.getSchemeObject(sourceSchemeName)
            if (!sourceScheme) {
                log.error("Source scheme '${sourceSchemeName}' not found")
                return [success: false, error: "Source scheme not found: ${sourceSchemeName}"]
            }
            
            // Check if new scheme already exists
            def existingScheme = permissionSchemeManager.getSchemeObject(newSchemeName)
            if (existingScheme) {
                log.warn("Scheme with name '${newSchemeName}' already exists")
                return [success: true, scheme: existingScheme, alreadyExists: true]
            }
            
            // Create new scheme
            def newScheme = permissionSchemeManager.copyScheme(sourceScheme)
            if (!newScheme) {
                log.error("Failed to copy scheme '${sourceSchemeName}'")
                return [success: false, error: "Failed to copy scheme"]
            }
            
            // Update scheme name
            newScheme.setName(newSchemeName)
            permissionSchemeManager.updateScheme(newScheme)
            
            log.warn("Successfully copied scheme. New scheme instance created: ${newScheme.name}")
            
            return [success: true, scheme: newScheme]
            
        } catch (Exception e) {
            log.error("Error copying permission scheme", e)
            return [success: false, error: "Failed to copy scheme: ${e.message}"]
        }
    }
    
    /**
     * Assign permission scheme to project
     */
    def assignSchemeToProject(String projectKey, String schemeName) {
        try {
            def project = projectManager.getProjectObjByKey(projectKey)
            if (!project) {
                log.error("Project not found: ${projectKey}")
                return [success: false, error: "Project not found"]
            }
            
            def scheme = permissionSchemeManager.getSchemeObject(schemeName)
            if (!scheme) {
                log.error("Permission scheme not found: ${schemeName}")
                return [success: false, error: "Permission scheme not found"]
            }
            
            // Assign scheme to project
            permissionSchemeManager.removeSchemesFromProject(project)
            permissionSchemeManager.addSchemeToProject(project, scheme)
            
            log.warn("Successfully assigned scheme '${schemeName}' to project '${projectKey}'")
            return [success: true]
            
        } catch (Exception e) {
            log.error("Failed to assign scheme to project", e)
            return [success: false, error: "Failed to assign scheme: ${e.message}"]
        }
    }
}

// Script execution entry point
def issue = issue  // The issue variable should be available in the script context

// Validate that issue exists before proceeding
if (!issue) {
    log.error("Script execution failed: issue variable is null or not available")
    return
}

log.warn("Starting project creation script execution for issue: ${issue.key}")

// Create and configure the project - all operations happen within the same lock
def projectScript = new ProjectCreationScript()
def result = projectScript.createProjectFromIssue(issue)

// The project creation method now handles both project creation and permission scheme assignment
// within the same cluster lock to ensure atomicity
if (!result.success) {
    log.error("Project creation workflow failed: ${result.error}")
} else {
    log.warn("Project creation workflow completed successfully")
    if (result.permissionError) {
        log.warn("Project created but with permission issues: ${result.permissionError}")
    }
}