# Jira Scripts

[![Code Quality](https://github.com/star7js/jira-scripts/workflows/Code%20Quality/badge.svg)](https://github.com/star7js/jira-scripts/actions)
[![Dependency Check](https://github.com/star7js/jira-scripts/workflows/Dependency%20Check/badge.svg)](https://github.com/star7js/jira-scripts/actions)
[![Release](https://github.com/star7js/jira-scripts/workflows/Release/badge.svg)](https://github.com/star7js/jira-scripts/actions)

A comprehensive collection of Groovy scripts for Atlassian Jira administration and automation. These scripts provide powerful tools for user management, permission auditing, Confluence integration, and REST API operations.

## 🚀 Features

- **User Management**: List users, add users to groups, create groups
- **Permission Auditing**: Comprehensive permission scheme analysis with violation detection
- **Confluence Integration**: Automated space creation and management
- **REST API Utilities**: Secure API calls using Trusted Applications
- **Audit Tools**: Data center auditing and user activity monitoring
- **Quality Assurance**: Automated code quality checks and security scanning

## 📋 Prerequisites

- **Jira Instance**: Cloud or Data Center (scripts work with both)
- **ScriptRunner**: Atlassian's ScriptRunner for Jira app
- **Permissions**: Admin-level access for most operations
- **Trusted Applications**: For REST API scripts (optional but recommended)

## 🛠️ Setup

1. **Install ScriptRunner** in your Jira instance
2. **Clone or download** this repository
3. **Upload scripts** to ScriptRunner console or scheduled jobs
4. **Configure** script parameters as needed (see individual script documentation)

## 🔧 Development

This project uses GitHub Actions for automated quality assurance:

- **Code Quality**: Syntax checking, import validation, and best practices
- **Security Scanning**: Checks for hardcoded credentials and security issues
- **Documentation**: Validates markdown formatting and structure
- **Automated Releases**: Creates releases and ZIP files on version tags
- **Dependency Monitoring**: Weekly checks for outdated references

## 📁 Script Categories

### User Management Scripts

| Script | Description | Use Case |
|--------|-------------|----------|
| `ListJiraUsers.groovy` | Generate HTML report of all users with details | User audits, reporting |
| `AddUserToGroup.groovy` | Add user to group with error handling | Bulk user management |
| `SimpleAddUserToGroup.groovy` | Simplified user-to-group addition | Quick operations |
| `CreateGroup.groovy` | Create new Jira groups | Group setup |

### Permission Management Scripts

| Script | Description | Use Case |
|--------|-------------|----------|
| `AuditPermissions.groovy` | Comprehensive permission scheme auditor | Security compliance |
| `ChangePermScheme.groovy` | Copy and apply permission schemes | Project setup |
| `AddProjectRoles.groovy` | Manage project roles | Role configuration |

### Audit and Monitoring Scripts

| Script | Description | Use Case |
|--------|-------------|----------|
| `AuditJiraDataCenter.groovy` | Access Jira auditing API | Compliance reporting |
| `FindUsersAddedToGroupXDaysAgoCloud.groovy` | Find recent group additions | Activity monitoring |

### Confluence Integration Scripts

| Script | Description | Use Case |
|--------|-------------|----------|
| `CreateConfluenceSpaceTest.groovy` | Standalone Confluence space creation with Application Links | Testing and development |
| `CreateConfluenceSpaceFromIssue.groovy` | Workflow post-function for space creation from Jira issues using Application Links | Production workflow automation |

### REST API Utilities

| Script | Description | Use Case |
|--------|-------------|----------|
| `RestCallJira.groovy` | Template for secure REST API calls | Custom integrations |

## 🔧 Configuration

### Common Configuration Parameters

Most scripts include configuration sections at the top. Common parameters include:

```groovy
// User Management
def userName = 'username'                    // Target username
def groupName = 'jira-users'                // Target group name

// Project Configuration
def projectKey = 'PROJECT'                  // Project key
def issueTypeName = 'Task'                  // Issue type name

// Permission Settings
def restrictedGroups = ['jira-software-users']  // Groups to audit
def restrictPublicAccess = true             // Block public access
```

### Trusted Applications Setup

For REST API scripts, configure Trusted Applications:

1. Go to **Administration** → **Security** → **Trusted Applications**
2. Create a new trusted application
3. Use the application ID in scripts requiring authentication

## 📖 Usage Examples

### List All Users
```groovy
// Run ListJiraUsers.groovy in ScriptRunner console
// Output: HTML table with user details
```

### Add User to Group
```groovy
// Edit AddUserToGroup.groovy configuration
def userName = 'john.doe'
def groupNameToAdd = 'developers'

// Run script in ScriptRunner console
// Output: Success/error message with logging
```

### Audit Permissions
```groovy
// Configure AuditPermissions.groovy
def restrictedGroupNames = ['jira-software-users', 'jira-servicedesk-users']
def violationProjectKey = 'AUDIT'

// Run as scheduled job or console script
// Output: Creates Jira issues for violations found
```

## 🔒 Security Considerations

- **Permissions**: Scripts require appropriate Jira permissions
- **Audit Logging**: All operations are logged for compliance
- **Error Handling**: Scripts include comprehensive error handling
- **Validation**: Input validation prevents invalid operations

## 🚨 Important Notes

1. **Backup**: Always backup your Jira instance before running scripts
2. **Testing**: Test scripts in a development environment first
3. **Permissions**: Ensure you have necessary permissions for operations
4. **Logging**: Check logs for detailed operation information

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Add your script with proper documentation
4. Test thoroughly
5. Submit a pull request

## 📝 Script Development Guidelines

When adding new scripts:

1. **Include configuration section** at the top
2. **Add comprehensive logging** using SLF4J or Log4j
3. **Implement error handling** with try-catch blocks
4. **Add input validation** for user parameters
5. **Document the script** with comments and usage examples
6. **Follow naming conventions** (PascalCase for script names)

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

For issues or questions:
1. Check the script comments for configuration help
2. Review Jira logs for error details
3. Ensure all prerequisites are met
4. Test in a development environment first

## 🔄 Version History

- **v1.1.1**: Enhanced CI/CD and security
  - Fixed GitHub Actions permissions
  - Added comprehensive CI badges
  - Improved release workflow

- **v1.1.0**: GitHub Actions and automation
  - Added comprehensive CI/CD workflows
  - Enhanced security scanning
  - Automated releases and dependency checks
  - Added issue and PR templates

- **v1.0**: Initial script collection
  - User management scripts
  - Permission auditing tools
  - Confluence integration
  - REST API utilities

## 🚀 Future Enhancements

We're continuously working to expand our script collection and improve functionality. Planned areas for future development include:

- **Issue Management**: Cloning, linking, moving, and bulk operations
- **Custom Field Management**: Creation, updates, and value setting
- **Workflow Automation**: Validation, transition checking, and condition management
- **Project Management**: Creation, components, and version management
- **Reporting and Analytics**: Statistics, activity tracking, and health metrics
- **Enhanced Security**: Advanced auditing, access analysis, and compliance tools

---

**Note**: These scripts are designed for Jira administrators and should be used responsibly. Always test in a development environment before running in production.
