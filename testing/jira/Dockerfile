# Use the official Atlassian Jira Software image
FROM atlassian/jira-software

# Copy the pre-configured backup into the import directory.
# Jira will automatically restore from this backup on first launch if the home directory is empty.
COPY ./import/jira-backup.zip /var/atlassian/application-data/jira/import/ 