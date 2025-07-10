# Contributing to Jira Scripts

Thank you for your interest in contributing to the Jira Scripts project! This document provides guidelines for contributing new scripts and improvements.

## 🤝 How to Contribute

### 1. Fork and Clone
1. Fork this repository
2. Clone your fork locally
3. Create a feature branch: `git checkout -b feature/your-script-name`

### 2. Script Development Guidelines

#### Script Structure
Every script should follow this structure:

```groovy
// =============================================================================
// Script Name: YourScriptName.groovy
// Description: Brief description of what the script does
// Author: Your Name
// Date: YYYY-MM-DD
// =============================================================================

import com.atlassian.jira.component.ComponentAccessor
// ... other imports

// --- Configuration Section ---
def configParam1 = 'value1'  // ← Change this
def configParam2 = 'value2'  // ← Change this
// --- End Configuration ---

// --- Logging Setup ---
def log = Logger.getLogger('com.example.YourScriptName')

// --- Main Logic ---
try {
    // Your script logic here
    log.info("Script started successfully")
    
    // ... implementation ...
    
    log.info("Script completed successfully")
    return "SUCCESS: Operation completed"
    
} catch (Exception e) {
    log.error("Script failed: ${e.message}", e)
    return "ERROR: ${e.message}"
}
```

#### Required Elements

1. **Header Comment Block**: Include script name, description, author, and date
2. **Configuration Section**: All configurable parameters at the top
3. **Proper Logging**: Use SLF4J or Log4j with descriptive messages
4. **Error Handling**: Wrap main logic in try-catch blocks
5. **Return Values**: Return meaningful success/error messages
6. **Input Validation**: Validate all user inputs before processing

#### Naming Conventions

- **Script Files**: PascalCase (e.g., `CreateUserGroup.groovy`)
- **Variables**: camelCase (e.g., `userName`, `groupName`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MAX_RETRY_ATTEMPTS`)
- **Loggers**: Use package-style naming (e.g., `com.example.ScriptName`)

### 3. Development Guidelines

Before submitting:

1. **Development Environment**: Run in a development Jira instance first
2. **Error Scenarios**: Test with invalid inputs and edge cases
3. **Permissions**: Verify script works with appropriate permission levels
4. **Logging**: Check that logs provide useful debugging information
5. **Performance**: Ensure script doesn't cause performance issues

### 4. Documentation

#### Script Documentation
Include comprehensive comments in your script:

```groovy
/**
 * This script performs [specific operation].
 * 
 * Prerequisites:
 * - User must have [specific permissions]
 * - [Other requirements]
 * 
 * Configuration:
 * - param1: Description of parameter
 * - param2: Description of parameter
 * 
 * Usage:
 * 1. Update configuration section
 * 2. Run in ScriptRunner console
 * 3. Check logs for results
 * 
 * Output:
 * - Success: Returns success message
 * - Error: Returns error message with details
 */
```

#### README Updates
If your script adds new functionality:

1. Add it to the appropriate category in the README
2. Update the script table with description and use case
3. Add any new configuration parameters to the examples
4. Update version history if needed

### 5. Submitting Your Contribution

1. **Commit Your Changes**:
   ```bash
   git add .
   git commit -m "Add: [Script Name] - [Brief description]"
   ```

2. **Push to Your Fork**:
   ```bash
   git push origin feature/your-script-name
   ```

3. **Create Pull Request**:
   - Use the PR template if available
   - Include description of what the script does
   - Mention any dependencies or special requirements
   - Include testing notes

### 6. Pull Request Guidelines

#### PR Title Format
```
Add: [Script Name] - [Brief description]
```

#### PR Description Template
```markdown
## Description
Brief description of what this script does and why it's useful.

## Changes Made
- [ ] New script added
- [ ] README updated
- [ ] Documentation included
- [ ] Tested in development environment

## Development
- [ ] Tested with valid inputs
- [ ] Tested with invalid inputs
- [ ] Tested error scenarios
- [ ] Verified logging works correctly

## Dependencies
List any new dependencies or requirements.

## Screenshots/Examples
If applicable, include screenshots or example outputs.
```

## 📋 Code Review Process

1. **Automated Checks**: Ensure all automated checks pass
2. **Code Review**: At least one maintainer will review your code
3. **Review**: Your script may be tested in a review environment
4. **Feedback**: Address any feedback or requested changes
5. **Merge**: Once approved, your contribution will be merged

## 🐛 Reporting Issues

When reporting issues:

1. **Use the Issue Template**: Fill out all required fields
2. **Include Details**: Provide Jira version, ScriptRunner version, error messages
3. **Reproduction Steps**: Include steps to reproduce the issue
4. **Logs**: Include relevant log entries
5. **Environment**: Mention if it's Cloud or Data Center

## 📚 Resources

- [Jira REST API Documentation](https://developer.atlassian.com/cloud/jira/platform/rest/)
- [ScriptRunner Documentation](https://scriptrunner.adaptavist.com/)
- [Groovy Documentation](https://groovy-lang.org/documentation.html)

## 🎯 Areas for Contribution

We're particularly interested in scripts for:

- **Advanced User Management**: Bulk operations, user synchronization
- **Project Automation**: Project creation, configuration templates
- **Integration Scripts**: Confluence, Bitbucket, other Atlassian products
- **Reporting**: Custom reports and analytics
- **Security**: Advanced permission auditing and compliance
- **Workflow Automation**: Custom workflow operations

## 📞 Questions?

If you have questions about contributing:

1. Check existing issues and discussions
2. Create a new issue with the "question" label
3. Reach out to maintainers directly

Thank you for contributing to the Jira Scripts project! 🚀 