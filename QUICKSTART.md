# Quick Start Guide

Get up and running with Jira Scripts in 5 minutes! 🚀

## ⚡ Quick Setup

### 1. Prerequisites (2 minutes)
- ✅ Jira instance (Cloud or Data Center)
- ✅ ScriptRunner for Jira app installed
- ✅ Admin-level permissions
- ✅ This repository downloaded

### 2. Choose Your First Script (1 minute)

**For User Management:**
- Start with `AddUserToGroup.groovy` - Add a user to a group
- Or `ListJiraUsers.groovy` - Generate a user report

**For Permission Auditing:**
- Start with `AuditPermissions.groovy` - Check permission violations

**For Confluence Integration:**
- Start with `CreateConfluenceSpaceFromIssue.groovy` - Create a space from Jira issues

### 3. Configure and Run (2 minutes)

#### Example: Add User to Group

1. **Open ScriptRunner Console**
   - Go to **Administration** → **ScriptRunner** → **Script Console**

2. **Copy the script**
   ```groovy
   // Copy contents of AddUserToGroup.groovy
   ```

3. **Update configuration**
   ```groovy
   def userName = 'john.doe'                    // ← Change this
   def groupNameToAdd = 'developers'            // ← Change this
   ```

4. **Run the script**
   - Click **Run** in ScriptRunner console
   - Check the output and logs

## 🎯 Common Use Cases

### I want to...

#### ...add a user to a group
```groovy
// Use AddUserToGroup.groovy
def userName = 'john.doe'
def groupNameToAdd = 'developers'
```

#### ...see all users in my Jira
```groovy
// Use ListJiraUsers.groovy
// No configuration needed - just run it!
```

#### ...check for permission violations
```groovy
// Use AuditPermissions.groovy
def restrictedGroupNames = ['jira-software-users']
def violationProjectKey = 'AUDIT'
```

#### ...create a Confluence space
```groovy
// Use CreateConfluenceSpaceFromIssue.groovy
def spaceName = "My Team Space"
def spaceKey = "TEAM"
def isOpsec = false
```

## 🔧 Configuration Cheat Sheet

### User Management
```groovy
def userName = 'username'              // Target username
def groupName = 'group-name'           // Target group name
def emailDomain = '@company.com'       // Email domain
```

### Project Configuration
```groovy
def projectKey = 'PROJECT'             // Project key
def issueTypeName = 'Task'             // Issue type
def priorityName = 'Medium'            // Priority
```

### Permission Settings
```groovy
def restrictedGroups = [
    'jira-software-users',
    'jira-servicedesk-users'
] as Set<String>

def restrictPublicAccess = true        // Block public access
def restrictLoggedInUsers = true       // Block 'Anyone logged in'
```

### Confluence Settings
```groovy
def confluenceSpaceName = 'Space Name' // Space name
def confluenceSpaceKey = 'SPACE'       // Space key
def isOpsecSpace = false               // OPSEC space flag
```

## 🚨 Safety First

### Before Running Any Script

1. **Backup your Jira instance**
2. **Test in development environment first**
3. **Check user permissions**
4. **Start with dry-run mode if available**

### Safe Testing
```groovy
// Many scripts support dry-run mode
def dryRun = true                      // ← Set to true for testing
```

## 📊 Understanding Output

### Success Messages
```
✅ SUCCESS: Added john.doe to group developers
✅ SUCCESS: Generated user report with 150 users
✅ SUCCESS: Created Confluence space TEAM
```

### Error Messages
```
❌ ERROR: User 'john.doe' not found
❌ ERROR: Group 'developers' not found
❌ ERROR: Permission denied
```

### Log Messages
```
INFO: Script started successfully
INFO: Found user: John Doe (john.doe)
INFO: Added user to group successfully
INFO: Script completed in 2.5s
```

## 🔍 Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| "Permission denied" | Check admin permissions |
| "User not found" | Verify username spelling |
| "Group not found" | Check group name |
| "Script timeout" | Increase timeout values |
| "API rate limit" | Add delays between calls |

### Debug Mode
```groovy
// Enable detailed logging
def logLevel = 'DEBUG'
def enableDetailedLogging = true
```

## 📈 Next Steps

### After Your First Script

1. **Explore other scripts** in the collection
2. **Check the examples** in `examples/README.md`
3. **Read the full documentation** in `README.md`
4. **Create your own scripts** using `templates/ScriptTemplate.groovy`

### Advanced Usage

- **Scheduled Jobs**: Set up scripts to run automatically
- **Bulk Operations**: Process multiple items at once
- **Custom Reports**: Generate HTML/CSV reports
- **Integration**: Connect with Confluence, Bitbucket, etc.

## 🆘 Need Help?

### Quick Resources
- 📖 **Full Documentation**: `README.md`
- 🔧 **Examples**: `examples/README.md`
- 📝 **Contributing**: `CONTRIBUTING.md`
- 📋 **Changelog**: `CHANGELOG.md`

### Getting Support
1. Check script comments for configuration help
2. Review Jira logs for error details
3. Test in development environment
4. Check ScriptRunner documentation

## 🎉 You're Ready!

You now have everything you need to start automating your Jira administration tasks. Happy scripting! 🚀

---

**Pro Tip**: Start with simple scripts and gradually work your way up to more complex automation. Each script is designed to be self-contained and well-documented. 