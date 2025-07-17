//file:noinspection DuplicatedCode
import com.atlassian.beehive.ClusterLockService
import com.atlassian.cache.Cache
import com.atlassian.cache.CacheManager
import com.atlassian.cache.CacheSettingsBuilder
import com.atlassian.jira.bc.project.ProjectCreationData
import com.atlassian.jira.bc.project.ProjectService
import com.atlassian.jira.bc.projectroles.ProjectRoleService
import com.atlassian.jira.cluster.ClusterManager
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.comments.CommentManager
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.config.manager.PrioritySchemeManager
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayoutImpl
import com.atlassian.jira.issue.fields.renderer.IssueRenderContext
import com.atlassian.jira.issue.issuetype.IssueType
import com.atlassian.jira.issue.priority.Priority
import com.atlassian.jira.issue.resolution.Resolution
import com.atlassian.jira.issue.status.Status
import com.atlassian.jira.permission.PermissionSchemeManager
import com.atlassian.jira.permission.PermissionScheme
import com.atlassian.jira.project.AssigneeTypes
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.project.ProjectCategory
import com.atlassian.jira.project.UpdateProjectParameters
import com.atlassian.jira.scheme.SchemeEntity
import com.atlassian.jira.security.roles.ProjectRoleActor
import com.atlassian.jira.security.roles.ProjectRoleManager
import com.atlassian.jira.transaction.TransactionSupport
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.util.SimpleErrorCollection
import com.google.common.collect.ImmutableList
import org.apache.log4j.Logger
import org.ofbiz.core.entity.GenericValue

import java.security.MessageDigest
import java.sql.Timestamp
import java.util.concurrent.TimeUnit

/**
 * Jira Data Center compatible project creation script
 * Handles multi-node environments with proper locking and caching
 * Fixed version with improved null handling and validation
 */
class ProjectCreationScript {

    private static final Logger log = Logger.getLogger(ProjectCreationScript.class)
    private static final String CACHE_NAME = "project-creation-cache"
    private static final String LOCK_PREFIX = "project-creation-lock-"
    private static final int LOCK_TIMEOUT_SECONDS = 120
    private static final int MAX_RETRIES = 5  // Reduced further based on suggestions
    private static final long PERMISSION_REF_SCHEME_ID = 10207L
    private static final long NOTIFICATION_REF_SCHEME_ID = 12300L

    // Project configuration constants
    private static final String PROJECT_TYPE = "Project Type"
    private static final String PROJECT_LEAD = "Project Lead / Project Admin"
    private static final String ADMIN_USERS = "List All Users That Should Have Administrator Rights On The Project?"
    private static final String SERVICE_DESK_USERS = "List All Users That Should Have Service Desk Team Rights On The Project?"
    private static final String ALL_EMPLOYEES_VIEW = "Should All Employees Have The Ability To View This Project?"
    private static final String ALL_EMPLOYEES_EDIT = "Should All Employees Have The Ability To Create/Edit Issues In This Project?"
    private static final String USE_WITH_JIP = "Will This Project Be Used With JIP Or Jira Integration Plus?"
    private static final String OPSEC_PROGRAM = "OPSEC Program to onboard to"
    private static final String NEW_PROJECT_NAME_FIELD = "New Project Or Team Space Name"
    private static final String PROJECT_CATEGORY = "Category"  // This is a custom field that is used to categorize the project
    private static final String ALL_EMPLOYEES_GROUP = "jira-users" // Default group for all employees

    // Role Names
    private static final String ROLE_ADMINISTRATORS = "Administrators"
    private static final String ROLE_SERVICE_DESK_TEAM = "Service Desk Team"
    private static final String ROLE_USERS = "Users"
    private static final String ROLE_DEVELOPERS = "Developers"

    private static final Map<String, String> PROJECT_TYPE_MAPPING = [
            "Software - Kanban - (Most Popular Choice)": "software",
            "Software - Scrum - (Good For Sprints)"   : "software",
            "Business - Project Management"           : "business",
            "Business - Task Management"              : "business",
            "Service Management - IT"                 : "service_desk",
            "Service Management - General"            : "service_desk"
    ]

    private static final Map<String, String> PROJECT_TEMPLATE_MAPPING = [
            "Software - Kanban - (Most Popular Choice)": "com.pyxis.greenhopper.jira:gh-kanban-template",
            "Software - Scrum - (Good For Sprints)": "com.pyxis.greenhopper.jira:gh-scrum-template",
            "Business - Project Management": "com.atlassian.jira-core-project-templates:jira-core-simplified-project-management",
            "Business - Task Management": "com.atlassian.jira-core-project-templates:jira-core-simplified-task-tracking",
            "Service Management - IT": "com.atlassian.servicedesk:simplified-it-service-desk",
            "Service Management - General": "com.atlassian.servicedesk:simplified-general-service-desk"
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
    private ProjectRoleService projectRoleService
    private TransactionSupport transactionSupport

    private boolean isTestMode = false // Set to true for Script Console testing

    // Constructor to set test mode if needed
    ProjectCreationScript(boolean testMode = false) {
        this.isTestMode = testMode
        initializeServices()
    }

    /**
     * Validate required custom fields exist before processing
     */
    private Map<String, Object> validateCustomFields() {
        if (isTestMode) {
            log.warn("Test mode: Skipping custom field validation")
            return [success: true, fields: [:]] // Mock success
        }
        def requiredFields = [NEW_PROJECT_NAME_FIELD, PROJECT_TYPE, PROJECT_LEAD, PROJECT_CATEGORY]
        def missingFields = []
        def fieldDetails = [:]

        requiredFields.each { fieldName ->
            def fields = customFieldManager.getCustomFieldObjectsByName(fieldName) // Typed as Collection
            def field = fields ? fields[0] : null // Get first or null
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
    private Map<String, Object> copyAndAssignPermissionScheme(Issue issue, Project project, Map<String, Object> projectDetails) {
        if (isTestMode) {
            log.warn("[TEST MODE] Skipping real permission scheme copy/assignment")
            return [success: true]  // Mock success in test mode
        }

        try {
            def permissionsScript = new CopyPermissionsScheme()

            def allEmployeesView = ((projectDetails["allEmployeesView"] ?: "") as String).toLowerCase() == "yes"
            def allEmployeesEdit = ((projectDetails["allEmployeesEdit"] ?: "") as String).toLowerCase() == "yes"
            def isOpsec = projectDetails["opsecProgram"] != null && ((projectDetails["opsecProgram"] ?: "") as String).trim() != ""

            def schemeResult = permissionsScript.execute(
                    project.name,
                    allEmployeesEdit,
                    allEmployeesView,
                    isOpsec,
                    isTestMode
            ) as Map<String, Object>

            if (schemeResult["success"] as boolean) {
                def assignResult = permissionsScript.assignSchemeToProject(
                        project.key,
                        (schemeResult["scheme"] as PermissionScheme).name
                ) as Map<String, Object>

                if (!(assignResult["success"] as boolean)) {
                    log.error("Failed to assign permission scheme: ${assignResult["error"]}")
                    return [success: false, error: assignResult["error"]]
                }

                log.warn("Successfully assigned permission scheme to project ${project.key}")
                return [success: true]
            } else {
                log.error("Failed to copy permission scheme: ${schemeResult["error"]}")
                return [success: false, error: schemeResult["error"]]
            }
        } catch (Exception e) {
            log.error("Error setting up permission scheme", e)
            return [success: false, error: "Permission scheme error: ${e.message}"] as Map<String, Object>
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
        this.projectRoleService = ComponentAccessor.getComponent(ProjectRoleService.class)
        this.transactionSupport = ComponentAccessor.getComponent(TransactionSupport.class)
    }

    /**
     * Main entry point for creating a project from an issue
     */
    Map<String, Object> createProjectFromIssue(Issue issue) {
        log.warn("Starting project creation from issue: ${issue?.key}")

        try {
            // Validate issue first
            if (!issue) {
                log.error("Issue is null")
                return [success: false, error: "Issue is null"] as Map<String, Object>
            }

            // Validate custom fields exist
            def fieldValidation = validateCustomFields()
            if (!fieldValidation.success) {
                addCommentToIssue(issue, "Error: ${fieldValidation.error}")
                return fieldValidation
            }

            // Extract project details from issue
            def details = extractProjectDetails(issue)
            log.warn("Extracted project details: ${details}")

            if (!details) {
                log.error("Failed to extract project details from issue ${issue.key}")
                addCommentToIssue(issue, "Error: Failed to extract project details. Check that all required custom fields are filled.")
                return [success: false, error: "Failed to extract project details"] as Map<String, Object>
            }

            // CRITICAL: Validate project key was generated successfully
            if (!details["projectKey"] || ((details["projectKey"] ?: "") as String).trim().isEmpty()) {
                log.error("Failed to generate valid project key for issue ${issue.key}")
                addCommentToIssue(issue, "Error: Could not generate project key. Check custom fields and issue summary.")
                return [success: false, error: "Project key generation failed"] as Map<String, Object>
            }

            // Validate project doesn't already exist
            if (projectExists(details["projectKey"] as String, details["projectName"] as String)) {
                log.warn("Project with key ${details.projectKey} already exists")
                addCommentToIssue(issue, "Error: Project with key ${details.projectKey} already exists")
                return [success: false, error: "Project with key ${details.projectKey} already exists"] as Map<String, Object>
            }

            // Acquire distributed lock for project creation using ClusterLockService
            def lockKey = LOCK_PREFIX + details["projectKey"]
            def lock = clusterLockService.getLockForName(lockKey)

            if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.error("Failed to acquire cluster lock for project creation: ${details.projectKey}")
                addCommentToIssue(issue, "Failed to create project: Another process is currently creating this project. Please try again.")
                return [success: false, error: "Another process is creating this project"] as Map<String, Object>
            }

            // Log which node acquired the lock
            def nodeId = ComponentAccessor.getComponent(ClusterManager.class)?.getNodeId() ?: "single-node"
            log.warn("Acquired cluster lock: ${lockKey} on node ${nodeId}")

            try {
                // Double-check project doesn't exist after acquiring lock
                if (projectExists(details["projectKey"] as String, details["projectName"] as String)) {
                    log.warn("Project with key ${details.projectKey} was created by another node")
                    addCommentToIssue(issue, "Project with key ${details.projectKey} was created by another process")
                    return [success: false, error: "Project was created by another process"] as Map<String, Object>
                }

                // Create project with retry logic
                def result = createProjectWithRetry(details)

                if (result["success"] as boolean) {
                    // Clear cache after successful creation
                    clearProjectCache(details["projectKey"] as String)
                    log.warn("Successfully created project: ${details.projectKey}")

                    // Copy and assign permissions scheme while still holding the lock
                    def permissionResult = copyAndAssignPermissionScheme(issue, result["project"] as Project, details)

                    if (permissionResult["success"] as boolean) {
                        def noSpaceProjectName = ((details["projectName"] ?: "") as String).replaceAll("\\s+", "").toLowerCase()

                        // Try to setup other schemes, catch failures to add comments but not rollback
                        try {
                            def notificationResult = setupNotificationScheme(result["project"] as Project, noSpaceProjectName)
                            if (!(notificationResult["success"] as boolean)) {
                                log.warn("Notification scheme setup failed: ${notificationResult["error"]}")
                                result["notificationError"] = notificationResult["error"]
                                addCommentToIssue(issue, "Project created, but notification scheme failed: ${notificationResult["error"]}")
                            }
                        } catch (Exception e) {
                            log.error("Notification scheme setup exception", e)
                            addCommentToIssue(issue, "Project created, but notification scheme failed: ${e.message}")
                        }

                        try {
                            def priorityResult = setupPriorityScheme(result["project"] as Project, noSpaceProjectName)
                            if (!(priorityResult["success"] as boolean)) {
                                log.warn("Priority scheme setup failed: ${priorityResult["error"]}")
                                result["priorityError"] = priorityResult["error"]
                                addCommentToIssue(issue, "Project created, but priority scheme failed: ${priorityResult["error"]}")
                            }
                        } catch (Exception e) {
                            log.error("Priority scheme setup exception", e)
                            addCommentToIssue(issue, "Project created, but priority scheme failed: ${e.message}")
                        }

                        try {
                            def fieldLayoutResult = setupFieldLayoutScheme(result["project"] as Project, noSpaceProjectName)
                            if (!(fieldLayoutResult["success"] as boolean)) {
                                log.warn("Field layout scheme setup failed: ${fieldLayoutResult["error"]}")
                                result["fieldLayoutError"] = fieldLayoutResult["error"]
                                addCommentToIssue(issue, "Project created, but field layout scheme failed: ${fieldLayoutResult["error"]}")
                            }
                        } catch (Exception e) {
                            log.error("Field layout scheme setup exception", e)
                            addCommentToIssue(issue, "Project created, but field layout scheme failed: ${e.message}")
                        }
                    }

                    // Get base URL for the hyperlink
                    def baseUrl = ComponentAccessor.applicationProperties.getString("jira.baseurl")
                    def projectLink = "[${details.projectKey}|${baseUrl}/projects/${details.projectKey}]"

                    if (permissionResult["success"] as boolean) {
                        addCommentToIssue(issue, "Successfully created project: ${projectLink} with permissions configured.")
                    } else {
                        addCommentToIssue(issue, "Project created: ${projectLink}, but permission scheme assignment failed: ${permissionResult["error"]}")
                        // Update the result to indicate partial success
                        result["permissionError"] = permissionResult["error"]
                    }
                } else {
                    addCommentToIssue(issue, "Failed to create project: ${result["error"]}")
                }

                return result

            } finally {
                lock.unlock()
            }

        } catch (Exception e) {
            log.error("Error creating project from issue ${issue?.key}", e)
            addCommentToIssue(issue, "Failed to create project: ${e.message}")
            return [success: false, error: "Unexpected error: ${e.message}"] as Map<String, Object>
        }
    }

    private Map<String, Object> setupNotificationScheme(Project project, String noSpaceProjectName) {
        try {
            def notificationSchemeManager = ComponentAccessor.getNotificationSchemeManager()
            def notificationSchemeName = "app-jira-" + noSpaceProjectName + "-notification-scheme"
            def refScheme = notificationSchemeManager.getSchemeObject("Default Notification Scheme")
            if (!refScheme) return [success: false, error: "Ref notification scheme not found"] as Map<String, Object>

            def newScheme = notificationSchemeManager.getSchemeObject(notificationSchemeName)
            if (!newScheme) {
                newScheme = notificationSchemeManager.createSchemeObject(notificationSchemeName, refScheme.getDescription())
                def refEntities = refScheme.getEntities()
                refEntities.each { entity ->
                    def eventTypeId = entity.getEntityTypeId()
                    def type = entity.getType()
                    def parameter = entity.getParameter()
                    def templateId = entity.getTemplateId()

                    if (eventTypeId == null || type == null) {
                        log.warn("Skipping invalid notification entity: eventTypeId=${eventTypeId}, type=${type}, parameter=${parameter}, templateId=${templateId}")
                        return
                    }

                    def schemeEntity = new SchemeEntity(type, parameter, eventTypeId)
                    if (templateId != null) {
                        schemeEntity.setTemplateId(templateId)
                    }
                    notificationSchemeManager.createSchemeEntity(notificationSchemeManager.getScheme(newScheme.id), schemeEntity)
                }
            }

            // Assign
            notificationSchemeManager.removeSchemesFromProject(project)
            notificationSchemeManager.addSchemeToProject(project, newScheme)

            return [success: true]
        } catch (Exception e) {
            log.error("Error setting notification scheme", e)
            return [success: false, error: e.message] as Map<String, Object>
        }
    }

    private Map<String, Object> setupPriorityScheme(Project project, String noSpaceProjectName) {
        try {
            def prioritySchemeManager = ComponentAccessor.getComponent(PrioritySchemeManager.class)
            def prioritySchemeName = "app-jira-" + noSpaceProjectName + "-priority-scheme"

            def fieldConfigScheme = prioritySchemeManager.getAllSchemes().find { it.name == prioritySchemeName }
            if (!fieldConfigScheme) {
                fieldConfigScheme = prioritySchemeManager.createWithDefaultMapping(prioritySchemeName, "Project-specific priorities", ImmutableList.of("2", "3", "4"))
                def fieldConfig = prioritySchemeManager.getFieldConfigForDefaultMapping(fieldConfigScheme)
                prioritySchemeManager.setDefaultOption(fieldConfig, "3")
            }

            // Assign if not already
            def currentScheme = prioritySchemeManager.getScheme(project)
            if (currentScheme?.name != prioritySchemeName) {
                if (currentScheme) {
                    prioritySchemeManager.unassignProject(currentScheme, project)
                }
                prioritySchemeManager.assignProject(fieldConfigScheme, project)
            }

            return [success: true]
        } catch (Exception e) {
            log.error("Error setting priority scheme", e)
            return [success: false, error: e.message] as Map<String, Object>
        }
    }

    private Map<String, Object> setupFieldLayoutScheme(Project project, String noSpaceProjectName) {
        try {
            def fieldLayoutManager = ComponentAccessor.getFieldLayoutManager()
            def fieldConfigSchemeName = "app-jira-" + noSpaceProjectName + "-field-config-scheme"

            // Create editable layout if not exists
            def editableFieldLayout = fieldLayoutManager.getEditableFieldLayouts().find { it.name == fieldConfigSchemeName }
            if (!editableFieldLayout) {
                def defaultFieldLayout = fieldLayoutManager.getEditableDefaultFieldLayout()
                def newEditable = new EditableFieldLayoutImpl(null, defaultFieldLayout.getFieldLayoutItems())
                newEditable.setName(fieldConfigSchemeName)
                newEditable.setDescription(fieldConfigSchemeName)
                editableFieldLayout = fieldLayoutManager.storeAndReturnEditableFieldLayout(newEditable)
            }

            // Create scheme if not exists
            def fieldLayoutScheme = fieldLayoutManager.getFieldLayoutSchemes().find { it.name == fieldConfigSchemeName }
            if (!fieldLayoutScheme) {
                fieldLayoutScheme = fieldLayoutManager.createFieldLayoutScheme(fieldConfigSchemeName, fieldConfigSchemeName)
                def entity = fieldLayoutManager.createFieldLayoutSchemeEntity(fieldLayoutScheme, null, editableFieldLayout.id)
                fieldLayoutManager.updateFieldLayoutSchemeEntity(entity)
                fieldLayoutManager.updateFieldLayoutScheme(fieldLayoutScheme)
            }

            // Assign if not already
            // Remove any existing scheme associations
            def currentSchemes = fieldLayoutManager.getFieldLayoutSchemes().findAll { scheme ->
                fieldLayoutManager.getProjectsUsing(scheme).any { p -> p.id == project.id }
            }
            if (currentSchemes) {
                currentSchemes.each { scheme ->
                    fieldLayoutManager.removeSchemeAssociation(project, scheme.id)
                }
            }
            // Assign the new scheme
            fieldLayoutManager.addSchemeAssociation(project, fieldLayoutScheme.id)

            return [success: true]
        } catch (Exception e) {
            log.error("Error setting field layout scheme", e)
            return [success: false, error: e.message] as Map<String, Object>
        }
    }

    /**
     * Extract project details from issue custom fields
     * Improved with better null handling and validation
     */
    private Map<String, Object> extractProjectDetails(Issue issue) {
        try {
            def details = [:] as Map<String, Object>

            if (isTestMode) {
                // Hardcoded mock data for test mode - customize as needed
                log.warn("Test mode: Using hardcoded mock project details")
                details.projectName = "Test Automation Project"
                details.projectType = "Software - Kanban - (Most Popular Choice)"
                details.projectLead = "admin"  // Replace with a valid username from your Jira
                details.adminUsers = "admin,user1"
                details.serviceDeskUsers = "servicedesk1,servicedesk2"
                details.allEmployeesView = "yes"
                details.allEmployeesEdit = "no"
                details.useWithJIP = "no"
                details.opsecProgram = ""
                details.projectCategory = "Development"
            } else {
                // Real extraction using custom fields
                details.projectName = getCustomFieldValue(issue, NEW_PROJECT_NAME_FIELD)?.trim() ?: issue.summary?.trim() ?: "Default Project"
                details.projectType = getCustomFieldValue(issue, PROJECT_TYPE)?.trim() ?: "Business - Project Management"  // Default fallback
                details.projectLead = getCustomFieldValue(issue, PROJECT_LEAD)?.trim()
                details.adminUsers = getCustomFieldValue(issue, ADMIN_USERS)?.trim()
                details.serviceDeskUsers = getCustomFieldValue(issue, SERVICE_DESK_USERS)?.trim()
                details.allEmployeesView = getCustomFieldValue(issue, ALL_EMPLOYEES_VIEW)?.toLowerCase()?.trim()
                details.allEmployeesEdit = getCustomFieldValue(issue, ALL_EMPLOYEES_EDIT)?.toLowerCase()?.trim()
                details.useWithJIP = getCustomFieldValue(issue, USE_WITH_JIP)?.toLowerCase()?.trim()
                details.opsecProgram = getCustomFieldValue(issue, OPSEC_PROGRAM)?.trim()
                details.projectCategory = getCustomFieldValue(issue, PROJECT_CATEGORY)?.trim()
            }

            // Generate key after name is set (applies to both test and real modes)
            details.projectKey = generateProjectKey(issue, details.projectName as String)

            // Validate required fields
            def required = ["projectName", "projectKey", "projectType", "projectLead"]
            def missing = required.findAll { !details[it] || (details[it] as String).trim().isEmpty() }
            if (missing) {
                log.error("Missing required fields: ${missing}")
                return null
            }

            // Validate project lead exists early (skip in test mode if needed)
            def userManager = ComponentAccessor.getUserManager()
            def leadUser = userManager.getUserByName(details.projectLead as String)
            if (!leadUser && !isTestMode) {  // Skip user validation in test mode
                log.error("Project Lead user '${details.projectLead}' not found.")
                return null
            }

            // Validate admin and service desk users if provided (skip in test mode)
            if (!isTestMode) {
                if (details.adminUsers) {
                    def invalidAdmins = parseUserList(details.adminUsers as String).findAll { !userManager.getUserByName(it) }
                    if (invalidAdmins) {
                        log.warn("Invalid admin users: ${invalidAdmins}")
                        // Continue but log; could throw if strict
                    }
                }
                if (details.serviceDeskUsers) {
                    def invalidService = parseUserList(details.serviceDeskUsers as String).findAll { !userManager.getUserByName(it) }
                    if (invalidService) {
                        log.warn("Invalid service desk users: ${invalidService}")
                    }
                }
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
    private Map<String, Object> createProjectWithRetry(Map<String, Object> projectDetails) {
        def attempts = 0
        def lastError = null

        while (attempts < MAX_RETRIES) {
            attempts++

            try {
                // Wrap in transaction
                def result = transactionSupport.executeInTransaction { ->
                    return createProject(projectDetails)
                }
                return result

            } catch (Exception e) {
                lastError = e
                log.warn("Project creation attempt ${attempts} failed: ${e.message}")

                if (attempts < MAX_RETRIES) {
                    // Use exponential backoff with jitter instead of simple sleep
                    def backoffTime = (long) (Math.pow(2, attempts) * 2000 + Math.random() * 1000)  // Add jitter
                    Thread.sleep(backoffTime)
                }
            }
        }

        log.error("Failed to create project after ${MAX_RETRIES} attempts", lastError)
        return [success: false, error: "Failed after ${MAX_RETRIES} attempts: ${lastError?.message}"] as Map<String, Object>
    }

    /**
     * Create the project and configure permissions
     */
    private Map<String, Object> createProject(Map<String, Object> projectDetails) {
        log.warn("Creating project with key: ${projectDetails.projectKey}")

        // Get the current user
        def currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser

        // Get the project lead user object from the username string
        def userManager = ComponentAccessor.getUserManager()
        def leadUser = userManager.getUserByName(projectDetails["projectLead"] as String)

        if (!leadUser) {
            log.error("Project Lead user '${projectDetails.projectLead}' not found.")
            return [success: false, error: "Project Lead user '${projectDetails.projectLead}' not found."] as Map<String, Object>
        }

        // Set project type
        def projectTypeKey = PROJECT_TYPE_MAPPING[projectDetails["projectType"]] ?: "business"

        // Determine template key based on project type
        def templateKey = PROJECT_TEMPLATE_MAPPING[projectDetails["projectType"]] ?: "com.atlassian.jira-core-project-templates:jira-core-simplified-project-management"

        // Get project category if specified
        ProjectCategory category = projectDetails["projectCategory"] ? projectManager.getAllProjectCategories().find { it.name == (projectDetails["projectCategory"] as String) } : null
        if (!category && projectDetails["projectCategory"]) {
            log.warn("Project category '${projectDetails["projectCategory"]}' not found - proceeding without category")
        }

        // Create project creation data using builder with reassignment to preserve types for static checking
        ProjectCreationData.Builder builder = new ProjectCreationData.Builder()
        builder = builder.withName(projectDetails["projectName"] as String)
        builder = builder.withKey(projectDetails["projectKey"] as String)
        builder = builder.withDescription((projectDetails["projectName"] as String)?.take(255) ?: "")
        builder = builder.withLead(leadUser)
        builder = builder.withAssigneeType(AssigneeTypes.PROJECT_LEAD as long)
        builder = builder.withType(projectTypeKey as String)
        builder = builder.withProjectTemplateKey(templateKey)
        def projectCreationData = builder.build()

        // Validate and create project
        def validationResult = projectService.validateCreateProject(currentUser, projectCreationData) as ProjectService.CreateProjectValidationResult

        if (!validationResult.valid) {
            def errors = validationResult.errorCollection.errors.collect { "${it.key}: ${it.value}" }.join(", ")
            log.error("Project validation failed: ${errors}")
            return [success: false, error: "Validation failed: ${errors}"] as Map<String, Object>
        }

        Project project
        try {
            project = projectService.createProject(validationResult)
        } catch (IllegalStateException e) {
            log.error("Project creation failed due to validation error after passing validation", e)
            return [success: false, error: "Creation failed: ${e.message}"] as Map<String, Object>
        } catch (Exception e) {
            log.error("Unexpected error during project creation", e)
            return [success: false, error: "Creation failed: ${e.message}"] as Map<String, Object>
        }

        if (!project) {
            log.error("Project creation returned null")
            return [success: false, error: "Creation failed: null project"] as Map<String, Object>
        }

        def maxPollAttempts = 10
        def pollInterval = 1000  // 1 sec
        def pollCount = 0
        while (projectManager.getProjectObjByKey(projectDetails.projectKey as String) == null && pollCount < maxPollAttempts) {
            Thread.sleep(pollInterval)
            pollCount++
            log.warn("Polling for project visibility: attempt ${pollCount}")
        }
        if (pollCount == maxPollAttempts) {
            throw new RuntimeException("Project not visible after polling")
        }

        // Set project category after creation if specified
        if (category) {
            try {
                // Update project with category using UpdateProjectParameters
                def updateParams = UpdateProjectParameters.forProject(project.id)
                    .name(project.name)
                    .description(project.description)
                    .leadUserKey(project.getProjectLead()?.key)
                    .url(project.url)
                    .assigneeType(project.assigneeType)
                    .avatarId(project.avatar?.id)
                    .projectCategoryId(category.id)
                def updatedProject = projectManager.updateProject(updateParams) as Project
                if (updatedProject) {
                    project = updatedProject
                    log.warn("Successfully updated project ${project.key} with category ${category.name}")
                } else {
                    log.warn("Failed to update project category, but proceeding")
                }
            } catch (Exception e) {
                log.warn("Error updating project category: ${e.message}", e)
            }
        }

        log.warn("Project created successfully, now configuring permissions for: ${project.key}")

        // Configure project permissions with try-catch to clean up on failure
        try {
            configureProjectPermissions(project, projectDetails)
        } catch (Exception e) {
            log.error("Failed to configure permissions for ${project.key} - deleting partial project", e)
            // Safely delete the partial project to avoid duplicates on retry
            def deleteValidation = projectService.validateDeleteProject(currentUser, project.key)
            if (deleteValidation.valid) {
                def deleteResult = projectService.deleteProject(currentUser, deleteValidation)
                if (deleteResult.valid) {
                    log.warn("Deleted partial project ${project.key}")
                } else {
                    log.error("Could not delete partial project ${project.key}: ${deleteResult.errorCollection}")
                }
            } else {
                log.error("Could not validate delete for partial project ${project.key}: ${deleteValidation.errorCollection}")
            }
            return [success: false, error: "Permission configuration failed: ${e.message}"] as Map<String, Object>
        }

        return [success: true, project: project]
    }

    /**
     * Configure project permissions based on custom field values - REVISED VERSION
     */
    private void configureProjectPermissions(Project project, Map<String, Object> details) {
        log.warn("Configuring permissions for project: ${project.key}")

        try {
            // Add administrators - with better validation
            if ((details["adminUsers"] as String)?.trim()) {
                def adminUsersList = parseUserList(details["adminUsers"] as String)
                log.warn("Adding admin users: ${adminUsersList}")
                addUsersToRole(project, ROLE_ADMINISTRATORS, adminUsersList)
            } else {
                log.warn("No admin users to add for project ${project.key}")
            }

            // Add service desk users - with better validation
            if ((details["serviceDeskUsers"] as String)?.trim()) {
                def serviceDeskUsersList = parseUserList(details["serviceDeskUsers"] as String)
                log.warn("Adding service desk users: ${serviceDeskUsersList}")
                addUsersToRole(project, ROLE_SERVICE_DESK_TEAM, serviceDeskUsersList)
            } else {
                log.warn("No service desk users to add for project ${project.key}")
            }

            // Configure all employees permissions
            if ((details["allEmployeesView"] as String) == "yes") {
                log.warn("Adding all employees to Users role")
                addGroupToRole(project, ALL_EMPLOYEES_GROUP, ROLE_USERS)
            }

            if ((details["allEmployeesEdit"] as String) == "yes") {
                log.warn("Adding all employees to Developers role")
                addGroupToRole(project, ALL_EMPLOYEES_GROUP, ROLE_DEVELOPERS)
            }

            // Log final role configuration for debugging
            logProjectRoles(project)

        } catch (Exception e) {
            log.error("Error configuring project permissions for ${project.key}", e)
            throw e // Re-throw to ensure the error is visible
        }
    }

    /**
     * Improved addUsersToRole method with better error handling - REVISED VERSION
     */
    private void addUsersToRole(Project project, String roleName, List<String> usernames) {
        try {
            def role = projectRoleManager.getProjectRole(roleName)
            if (!role) {
                log.error("Role not found: ${roleName}")
                throw new RuntimeException("Required role missing: ${roleName}")  // Fail fast
            }

            log.warn("Adding ${usernames.size()} users to role ${roleName} in project ${project.key}")

            List<String> validUserKeys = []
            def invalidUsers = []

            usernames.each { username ->
                if (!username?.trim()) return
                def user = ComponentAccessor.userManager.getUserByName(username.trim())
                if (user) {
                    validUserKeys << user.key
                    log.warn("Found user: ${username} (key: ${user.key})")
                } else {
                    invalidUsers << username
                    log.error("User not found: ${username}")
                }
            }

            if (invalidUsers) {
                log.error("Invalid users for role ${roleName}: ${invalidUsers}")
            }

            if (validUserKeys) {
                def errorCollection = new SimpleErrorCollection()
                projectRoleService.addActorsToProjectRole(validUserKeys, role, project, ProjectRoleActor.USER_ROLE_ACTOR_TYPE, errorCollection)
                if (errorCollection.hasAnyErrors()) {
                    log.error("Error adding users to role ${roleName}: ${errorCollection.errors}")
                } else {
                    log.warn("Successfully added ${validUserKeys.size()} users to role ${roleName}")
                }
            } else {
                log.warn("No valid users to add to role ${roleName}")
            }
        } catch (Exception e) {
            log.error("Error adding users to role ${roleName}", e)
            throw e
        }
    }

    /**
     * Improved addGroupToRole method - REVISED VERSION
     */
    private void addGroupToRole(Project project, String groupName, String roleName) {
        try {
            def role = projectRoleManager.getProjectRole(roleName)
            if (!role) {
                log.error("Role not found: ${roleName}")
                throw new RuntimeException("Required role missing: ${roleName}")
            }

            // Check if group exists
            def groupManager = ComponentAccessor.getGroupManager()
            if (!groupManager.getGroup(groupName)) {
                log.error("Group not found: ${groupName}")
                throw new RuntimeException("Required group missing: ${groupName}")
            }

            def errorCollection = new SimpleErrorCollection()
            projectRoleService.addActorsToProjectRole([groupName], role, project, ProjectRoleActor.GROUP_ROLE_ACTOR_TYPE, errorCollection)
            if (errorCollection.hasAnyErrors()) {
                log.error("Error adding group ${groupName} to role ${roleName}: ${errorCollection.errors}")
            } else {
                log.warn("Successfully added group ${groupName} to role ${roleName}")
            }
        } catch (Exception e) {
            log.error("Error adding group ${groupName} to role ${roleName}", e)
            throw e
        }
    }

    /**
     * New method to log current project roles for debugging - NEW METHOD
     */
    private void logProjectRoles(Project project) {
        try {
            def roles = [ROLE_ADMINISTRATORS, ROLE_SERVICE_DESK_TEAM, ROLE_USERS, ROLE_DEVELOPERS]

            roles.each { roleName ->
                def role = projectRoleManager.getProjectRole(roleName)
                if (role) {
                    def actors = projectRoleManager.getProjectRoleActors(role, project)
                    def users = actors.getUsers().collect { it.name }
                    def groups = actors.getRoleActors().findAll {
                        it.type == ProjectRoleActor.GROUP_ROLE_ACTOR_TYPE
                    }.collect { it.parameter }

                    log.warn("Role ${roleName}: Users=${users}, Groups=${groups}")
                } else {
                    log.warn("Role ${roleName}: NOT FOUND")
                }
            }
        } catch (Exception e) {
            log.error("Error logging project roles", e)
        }
    }

    /**
     * Enhanced helper method to get custom field values
     * Handles Options, multi-selects, and various field types
     * Improved with better null handling and logging
     */
    private String getCustomFieldValue(Issue issue, String fieldName) {
        try {
            def fields = customFieldManager.getCustomFieldObjectsByName(fieldName)
            def customField = fields ? fields[0] : null
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

            baseKey = baseKey.take(10) // Take up to 10 characters for the base key

            // Check if this simple, user-friendly key already exists.
            if (!projectExists(baseKey, null)) {
                log.warn("Using user-friendly project key: ${baseKey}")
                return baseKey
            }

            // If it exists, generate a unique key by adding a hash.
            log.warn("Project key '${baseKey}' already exists. Generating a unique key.")
            def keyForHashing = baseKey.take(4) // Shorten to make room for the hash suffix

            // Generate a hash of the issue key + timestamp for uniqueness
            def uniqueString = "${issue.key}-${System.currentTimeMillis()}-${Thread.currentThread().id}-${Math.random()}"
            def messageDigest = MessageDigest.getInstance("MD5")
            def hashBytes = messageDigest.digest(uniqueString.getBytes())
            def hashString = hashBytes.encodeHex().toString().toUpperCase().replaceAll("[^A-Z0-9]", "")

            // Combine base key with hash suffix (ensure total length 2-10 chars)
            def maxSuffixLength = Math.max(0, 10 - keyForHashing.length())
            def suffix = hashString.take(maxSuffixLength)
            def projectKey = "${keyForHashing}${suffix}".take(10)

            // Final validation and fallback to a completely safe key
            if (!projectKey || !projectKey.matches('^[A-Z][A-Z0-9]{1,9}$')) {
                log.error("Invalid project key generated: '${projectKey}'. Falling back to default.")
                def timestamp = System.currentTimeMillis().toString()[-6..-1]
                def random = Math.abs(new Random().nextInt(999)).toString().padLeft(3, '0')
                projectKey = "PR${timestamp}${random}".take(10)
            }

            log.warn("Generated unique project key: ${projectKey} for issue ${issue.key}")
            return projectKey

        } catch (Exception e) {
            log.error("Error generating project key", e)
            // Emergency fallback
            def timestamp = System.currentTimeMillis().toString()[-6..-1]
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

        if (isTestMode) {
            log.warn("[TEST MODE] Skipping real project existence check - assuming does not exist")
            return false  // Mock non-existence for testing
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

        // Handle multiple delimiters and clean up the list
        return userList.split(/[,;|\n\r]+/)
                .collect { it.trim() }
                .findAll { it && !it.isEmpty() }
                .unique()
    }

    private Cache<String, Boolean> getProjectCache() {
        return cacheManager.getCache(CACHE_NAME, null,
                new CacheSettingsBuilder()
                        .expireAfterAccess(5, TimeUnit.MINUTES)
                        .maxEntries(100)  // Reduced based on suggestions
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
            if (isTestMode) {
                log.warn("[TEST MODE] Would add comment to ${issue?.key ?: 'mock'}: ${message}")
                return
            }
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
    private static final String EDIT_SCHEME = "app-jira-all-employees-edit-permissions-scheme"
    // For projects where all employees can edit
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
    Map<String, Object> execute(String projectName, boolean allEmployeesCanEdit, boolean allEmployeesCanView, boolean isOpsec, boolean isTestMode) {
        log.warn("Executing CopyPermissionsScheme for project: ${projectName}")

        try {
            // Validate parameters
            if (!projectName?.trim()) {
                log.error("Project name is required")
                return [success: false, error: "Project name is required"] as Map<String, Object>
            }

            if (allEmployeesCanEdit && !allEmployeesCanView) {
                log.error("Invalid combination: allEmployeesCanEdit is true but allEmployeesCanView is false")
                return [success: false, error: "Invalid permission combination"] as Map<String, Object>
            }

            // Determine source scheme
            String sourceSchemeName = determineSourceScheme(isOpsec, allEmployeesCanEdit, allEmployeesCanView)
            String newSchemeName = generateSchemeName(projectName)

            // NEW: Append timestamp for uniqueness in test mode
            if (isTestMode) {  // Assume isTestMode is accessible or passed in
                newSchemeName += "-test-${System.currentTimeMillis()}"
                log.warn("[TEST MODE] Appended unique suffix to scheme name: ${newSchemeName}")
            }

            log.warn("Source scheme: ${sourceSchemeName}, New scheme: ${newSchemeName}")

            // NEW: Check if scheme already exists
            def existingScheme = permissionSchemeManager.getSchemeObject(newSchemeName)
            if (existingScheme) {
                if (isTestMode) {
                    // In test mode, delete existing for cleanup (optional; remove if too aggressive)
                    log.warn("[TEST MODE] Deleting existing scheme '${newSchemeName}' for re-creation")
                    permissionSchemeManager.deleteScheme(existingScheme.id)
                } else {
                    log.warn("Scheme '${newSchemeName}' already exists - reusing it")
                    return [success: true, scheme: existingScheme]  // Or error if you don't want reuse
                }
            }

            // Copy the permission scheme
            def sourceScheme = permissionSchemeManager.getSchemeObject(sourceSchemeName)
            if (!sourceScheme) {
                log.error("Source scheme '${sourceSchemeName}' not found")
                return [success: false, error: "Source scheme not found: ${sourceSchemeName}"] as Map<String, Object>
            }

            def newScheme = permissionSchemeManager.createSchemeObject(newSchemeName, sourceScheme.getDescription())
            if (!newScheme) {
                log.error("Failed to create new scheme '${newSchemeName}'")
                return [success: false, error: "Failed to create scheme"] as Map<String, Object>
            }

            // FIXED: Use getScheme() to get GenericValue (suppress deprecation if needed)
            @SuppressWarnings("deprecation")
            def sourceGV = permissionSchemeManager.getSchemeObject(sourceScheme.id)  // Returns GenericValue
            def sourceEntities = permissionSchemeManager.getEntities(sourceGV as GenericValue)  // Expects GenericValue

            sourceEntities.each { GenericValue entity ->
                def type = entity.getString("type")
                def parameter = entity.getString("parameter")
                def permission = entity.getLong("permission")

                log.warn("Processing permission entity: type='${type}', parameter='${parameter}', permission=${permission}")

                if (type == null || type.trim().isEmpty() || permission == null) {
                    log.warn("Skipping invalid entity: type='${type}', parameter='${parameter}', permission=${permission}")
                    return
                }

                def schemeEntity = new SchemeEntity(type, parameter, permission)
                
                // FIXED: Get GenericValue for new scheme
                def newGV = permissionSchemeManager.getSchemeObject(newScheme.id)  // Returns GenericValue
                permissionSchemeManager.createSchemeEntity(newGV as GenericValue, schemeEntity)  // Expects GenericValue
            }

            def fetchedNewScheme = permissionSchemeManager.getSchemeObject(newSchemeName)
            if (!fetchedNewScheme) {
                return [success: false, error: "New scheme not fetchable after creation"]
            }

            return [success: true, scheme: newScheme]

        } catch (Exception e) {
            log.error("Failed to copy permission scheme", e)
            return [success: false, error: "Unexpected error: ${e.message}"] as Map<String, Object>
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
     * Assign permission scheme to project
     */
    Map<String, Object> assignSchemeToProject(String projectKey, String schemeName) {
        try {
            def project = projectManager.getProjectObjByKey(projectKey)
            if (!project) {
                log.error("Project not found: ${projectKey}")
                return [success: false, error: "Project not found"] as Map<String, Object>
            }

            def scheme = permissionSchemeManager.getSchemeObject(schemeName)
            if (!scheme) {
                log.error("Permission scheme not found: ${schemeName}")
                return [success: false, error: "Permission scheme not found"] as Map<String, Object>
            }

            // Assign scheme to project
            permissionSchemeManager.removeSchemesFromProject(project)
            permissionSchemeManager.addSchemeToProject(project, scheme)

            log.warn("Successfully assigned scheme '${schemeName}' to project '${projectKey}'")
            return [success: true]

        } catch (Exception e) {
            log.error("Failed to assign scheme to project", e)
            return [success: false, error: "Failed to assign scheme: ${e.message}"] as Map<String, Object>
        }
    }
}

// Mock Issue for testing in Script Console - implementing Issue with all required stubs to satisfy static type checking
class MockIssue implements Issue {
    String key = "TEST-1"
    String summary = "Test Summary"

    // Explicit stubs for commonly used methods
    Long getId() { return 1L }

    String getKey() { return key }

    String getSummary() { return summary }

    Object getCustomFieldValue(Object cf) { return null }

    Project getProjectObject() { return null }

    IssueType getIssueType() { return null }

    String getString(String field) { return null }

    Timestamp getTimestamp(String field) { return null }

    Long getLong(String field) { return null }

    GenericValue getGenericValue() { return null }

    void store() { /* do nothing for mock */ }

    Long getProjectId() { return null }

    // Additional stubs for all reported missing methods
    IssueType getIssueTypeObject() { return null }

    String getIssueTypeId() { return null }

    GenericValue getProject() { return null }

    ApplicationUser getAssigneeUser() { return null }

    ApplicationUser getAssignee() { return null }

    String getAssigneeId() { return null }

    Collection getComponentObjects() { return [] }

    Collection getComponents() { return [] }

    ApplicationUser getReporterUser() { return null }

    ApplicationUser getReporter() { return null }

    String getReporterId() { return null }

    ApplicationUser getCreator() { return null }

    String getCreatorId() { return null }

    String getDescription() { return null }

    String getEnvironment() { return null }

    Collection getAffectedVersions() { return [] }

    Collection getFixVersions() { return [] }

    Timestamp getDueDate() { return null }

    GenericValue getSecurityLevel() { return null }

    Long getSecurityLevelId() { return null }

    Priority getPriority() { return null }

    Priority getPriorityObject() { return null }

    String getResolutionId() { return null }

    Resolution getResolution() { return null }

    Resolution getResolutionObject() { return null }

    Long getNumber() { return null }

    Long getVotes() { return null }

    Long getWatches() { return null }

    Timestamp getCreated() { return null }

    Timestamp getUpdated() { return null }

    Timestamp getResolutionDate() { return null }

    Long getWorkflowId() { return null }

    Object getCustomFieldValue(CustomField field) { return null }

    Status getStatus() { return null }

    String getStatusId() { return null }

    Status getStatusObject() { return null }

    Long getOriginalEstimate() { return null }

    Long getEstimate() { return null }

    Long getTimeSpent() { return null }

    Object getExternalFieldValue(String fieldId) { return null }

    boolean isSubTask() { return false }

    Long getParentId() { return null }

    boolean isCreated() { return true }

    Issue getParentObject() { return null }

    GenericValue getParent() { return null }

    Collection getSubTasks() { return [] }

    Collection getSubTaskObjects() { return [] }

    boolean isEditable() { return false }

    IssueRenderContext getIssueRenderContext() { return null }

    Collection getAttachments() { return [] }

    Set getLabels() { return [] as Set }

    boolean isArchived() { return false }

    ApplicationUser getArchivedByUser() { return null }

    String getArchivedById() { return null }

    Timestamp getArchivedDate() { return null }

    // Dynamic handler for any other missing methods (fallback)
    def methodMissing(String name, args) {
        log.warn("[MOCK] Unimplemented method called: ${name}")
        if (name.startsWith('get') || name.startsWith('is')) {
            return null
        } else if (name.startsWith('set')) {
            return  // void
        }
        throw new MissingMethodException(name, this.class, args)
    }

    def propertyMissing(String name) {
        log.warn("[MOCK] Unimplemented property accessed: ${name}")
        return null
    }

    // Add more if needed
}

// Script execution entry point
// def issue = issue  // Comment out original

def issue = new MockIssue()  // Use mock for testing

// Validate that issue exists before proceeding
if (!issue) {
    log.error("Script execution failed: issue variable is null or not available")
    return
}

log.warn("Starting project creation script execution for issue: ${issue.key}")

// Create and configure the project - all operations happen within the same lock
def projectScript = new ProjectCreationScript(true) // Enable test mode

Map<String, Object> result = projectScript.createProjectFromIssue(issue)

if (!(result["success"] as boolean)) {
    log.error("Project creation workflow failed: ${result["error"]}")
} else {
    log.warn("Project creation workflow completed successfully")
    if (result["permissionError"]) {
        log.warn("Project created but with permission issues: ${result["permissionError"]}")
    }
}
