# Script Examples

This directory contains example configurations and usage patterns for the Jira Scripts collection.

## 📁 Example Categories

### User Management Examples

#### Basic User Operations
```groovy
// Example: Add multiple users to a group
def usersToAdd = ['john.doe', 'jane.smith', 'bob.wilson']
def targetGroup = 'developers'

usersToAdd.each { username ->
    def userName = username
    def groupNameToAdd = targetGroup
    // Run AddUserToGroup.groovy logic here
}
```

#### User Reporting
```groovy
// Example: Generate user report for specific groups
def targetGroups = ['developers', 'qa-team', 'product-managers']
def reportData = [:]

targetGroups.each { groupName ->
    def groupUsers = userUtil.getUserNamesInGroup(groupName)
    reportData[groupName] = groupUsers.size()
}
```

### Permission Management Examples

#### Permission Scheme Copying
```groovy
// Example: Copy permission scheme to multiple projects
def sourceScheme = 'Standard Permissions'
def targetProjects = ['PROJ1', 'PROJ2', 'PROJ3']
def newSchemeSuffix = 'Custom Permissions'

targetProjects.each { projectKey ->
    def TARGET_PROJECT_KEY = projectKey
    def SOURCE_SCHEME_NAME = sourceScheme
    def NEW_SCHEME_NAME_SUFFIX = newSchemeSuffix
    // Run ChangePermScheme.groovy logic here
}
```

#### Permission Auditing
```groovy
// Example: Audit specific permission types
def permissionTypesToAudit = [
    'BROWSE_PROJECTS',
    'ADMINISTER_PROJECTS',
    'CREATE_ISSUES'
]

def auditResults = permissionTypesToAudit.collect { permissionType ->
    // Audit specific permission type
    return [permission: permissionType, violations: []]
}
```

### Confluence Integration Examples

#### Bulk Space Creation
```groovy
// Example: Create multiple Confluence spaces using standalone script
def spacesToCreate = [
    [name: 'Development Team', key: 'DEV', isOpsec: false],
    [name: 'QA Team', key: 'QA', isOpsec: false],
    [name: 'Security Team', key: 'SEC', isOpsec: true]
]

spacesToCreate.each { spaceConfig ->
    def testValues = [
        spaceName: spaceConfig.name,
        spaceKey: spaceConfig.key,
        isOpsec: spaceConfig.isOpsec
    ]
    // Run CreateConfluenceSpaceTest.groovy logic here
}
```

#### Workflow-Based Space Creation
```groovy
// Example: Configure workflow post-function for space creation
// This would be configured in the workflow post-function settings

// Custom field configuration for CreateConfluenceSpaceFromIssue.groovy
def customFields = [
    spaceName: "New Desired Space Name",
    spaceKey: "Desired Space Key", 
    purpose: "Describe the purpose",
    summary: "Summary of the Space"
]

// Workflow transition setup:
// 1. Create transition from "To Do" to "In Progress"
// 2. Add post-function: "Script Post-function"
// 3. Select CreateConfluenceSpaceFromIssue.groovy
// 4. Configure custom fields on issue type
```

### REST API Examples

#### Batch Issue Operations
```groovy
// Example: Update multiple issues
def issueKeys = ['PROJ-123', 'PROJ-124', 'PROJ-125']
def updatePayload = [
    fields: [
        priority: [name: 'High'],
        assignee: [name: 'john.doe']
    ]
]

issueKeys.each { issueKey ->
    def ISSUE_KEY_TO_FETCH = issueKey
    // Modify RestCallJira.groovy to use PUT method
    // and include updatePayload in request body
}
```

## 🔧 Configuration Templates

### Standard Configuration Block
```groovy
// =============================================================================
// Configuration Template
// =============================================================================

// User Management
def userName = 'username'                    // Target username
def groupName = 'jira-users'                // Target group name
def emailDomain = '@company.com'            // Email domain for new users

// Project Configuration
def projectKey = 'PROJECT'                  // Project key
def projectName = 'Project Name'            // Project name
def issueTypeName = 'Task'                  // Issue type name
def priorityName = 'Medium'                 // Priority name

// Permission Settings
def restrictedGroups = [
    'jira-software-users',
    'jira-servicedesk-users'
] as Set<String>

def restrictedRoles = [
    'jira-software',
    'jira-servicedesk'
] as Set<String>

def restrictPublicAccess = true             // Block public access
def restrictLoggedInUsers = true            // Block 'Anyone logged in'

// Confluence Settings
def confluenceSpaceName = 'Space Name'      // Confluence space name
def confluenceSpaceKey = 'SPACE'            // Confluence space key
def isOpsecSpace = false                    // OPSEC space flag

// REST API Settings
def apiEndpoint = '/rest/api/2'             // API endpoint
def timeoutMs = 30000                       // Request timeout
def maxRetries = 3                          // Maximum retry attempts

// Logging Configuration
def logLevel = 'INFO'                       // Log level (DEBUG, INFO, WARN, ERROR)
def enableDetailedLogging = false           // Enable detailed logging

// =============================================================================
```

### Error Handling Template
```groovy
// =============================================================================
// Error Handling Template
// =============================================================================

def executeWithRetry(Closure operation, int maxRetries = 3) {
    int attempts = 0
    while (attempts < maxRetries) {
        try {
            return operation.call()
        } catch (Exception e) {
            attempts++
            log.warn("Attempt ${attempts} failed: ${e.message}")
            
            if (attempts >= maxRetries) {
                log.error("All ${maxRetries} attempts failed", e)
                throw e
            }
            
            // Wait before retry (exponential backoff)
            Thread.sleep(1000 * attempts)
        }
    }
}

def validateInputs() {
    def errors = []
    
    if (!userName?.trim()) {
        errors << "Username is required"
    }
    
    if (!groupName?.trim()) {
        errors << "Group name is required"
    }
    
    if (errors) {
        throw new IllegalArgumentException("Validation failed: ${errors.join(', ')}")
    }
}

// =============================================================================
```

## 📊 Reporting Templates

### HTML Report Template
```groovy
def generateHtmlReport(String title, List data, List columns) {
    StringBuilder html = new StringBuilder()
    
    html.append("""
        <html>
        <head>
            <title>${title}</title>
            <style>
                table { border-collapse: collapse; width: 100%; }
                th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                th { background-color: #f2f2f2; }
                tr:nth-child(even) { background-color: #f9f9f9; }
                .error { color: red; }
                .success { color: green; }
                .warning { color: orange; }
            </style>
        </head>
        <body>
            <h1>${title}</h1>
            <p>Generated on: ${new Date()}</p>
            <table>
                <thead>
                    <tr>
    """)
    
    columns.each { column ->
        html.append("<th>${column}</th>")
    }
    
    html.append("</tr></thead><tbody>")
    
    data.each { row ->
        html.append("<tr>")
        row.each { cell ->
            html.append("<td>${cell}</td>")
        }
        html.append("</tr>")
    }
    
    html.append("</tbody></table></body></html>")
    
    return html.toString()
}
```

### CSV Report Template
```groovy
def generateCsvReport(String title, List data, List columns) {
    StringBuilder csv = new StringBuilder()
    
    // Add header
    csv.append(columns.join(','))
    csv.append('\n')
    
    // Add data rows
    data.each { row ->
        csv.append(row.collect { cell ->
            // Escape commas and quotes in CSV
            if (cell?.toString()?.contains(',') || cell?.toString()?.contains('"')) {
                "\"${cell.toString().replace('"', '""')}\""
            } else {
                cell?.toString() ?: ''
            }
        }.join(','))
        csv.append('\n')
    }
    
    return csv.toString()
}
```

## 🚀 Advanced Usage Patterns

### Scheduled Job Configuration
```groovy
// Example: Configure script for scheduled execution
def scheduledJobConfig = [
    name: 'Daily Permission Audit',
    description: 'Audit permission schemes daily',
    cronExpression: '0 2 * * *', // 2 AM daily
    enabled: true,
    runAsUser: 'admin',
    scriptFile: 'AuditPermissions.groovy'
]
```

### Bulk Operations
```groovy
// Example: Process large datasets in batches
def batchSize = 100
def allItems = getAllItems() // Your data source

allItems.collate(batchSize).each { batch ->
    log.info("Processing batch of ${batch.size()} items")
    
    batch.each { item ->
        try {
            processItem(item)
        } catch (Exception e) {
            log.error("Failed to process item ${item}: ${e.message}")
        }
    }
    
    // Optional: Add delay between batches
    Thread.sleep(1000)
}
```

## 📝 Best Practices

1. **Always validate inputs** before processing
2. **Use meaningful variable names** and comments
3. **Implement proper error handling** with try-catch blocks
4. **Add comprehensive logging** for debugging
5. **Test with small datasets** before running on production
6. **Use batch processing** for large operations
7. **Include progress indicators** for long-running scripts
8. **Return meaningful results** for success/failure cases

## 🔍 Troubleshooting

### Common Issues

1. **Permission Denied**: Check user permissions and group memberships
2. **Timeout Errors**: Increase timeout values for large operations
3. **Memory Issues**: Use batch processing for large datasets
4. **API Rate Limits**: Add delays between API calls
5. **Invalid Input**: Validate all user inputs before processing

### Debugging Tips

1. **Enable DEBUG logging** for detailed information
2. **Check Jira logs** for system-level errors
3. **Test with minimal data** to isolate issues
4. **Use try-catch blocks** to capture specific errors
5. **Validate configuration** before running scripts

---

For more examples and patterns, check the individual script files in the main directory. 