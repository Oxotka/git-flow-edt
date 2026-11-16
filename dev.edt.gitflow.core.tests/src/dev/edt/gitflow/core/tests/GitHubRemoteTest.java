package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.edt.gitflow.core.GitHubRemote;

public class GitHubRemoteTest
{
    @Test
    public void parsesGithubHttps()
    {
        var remote = GitHubRemote.parse("https://github.com/Oxotka/git-flow-edt.git"); //$NON-NLS-1$
        assertEquals("github.com", remote.host()); //$NON-NLS-1$
        assertEquals("Oxotka/git-flow-edt", remote.projectPath()); //$NON-NLS-1$
        assertEquals("Oxotka", remote.owner()); //$NON-NLS-1$
        assertEquals("https://api.github.com", remote.apiBase()); //$NON-NLS-1$
        assertEquals("https://github.com/Oxotka/git-flow-edt", remote.webBase()); //$NON-NLS-1$
    }

    @Test
    public void parsesScpLikeRemote()
    {
        var remote = GitHubRemote.parse("git@github.com:owner/repo.git"); //$NON-NLS-1$
        assertEquals("github.com", remote.host()); //$NON-NLS-1$
        assertEquals("https://api.github.com", remote.apiBase()); //$NON-NLS-1$
        assertEquals("owner/repo", remote.projectPath()); //$NON-NLS-1$
    }

    @Test
    public void buildsEnterpriseApiBase()
    {
        var remote = GitHubRemote.parse("ssh://git@ghe.corp:2222/team/repo.git"); //$NON-NLS-1$
        assertEquals("ghe.corp", remote.host()); //$NON-NLS-1$
        assertEquals("team/repo", remote.projectPath()); //$NON-NLS-1$
        assertEquals("https://ghe.corp/api/v3", remote.apiBase()); //$NON-NLS-1$
    }

    @Test
    public void buildsFromServerRoot()
    {
        var remote = GitHubRemote.forServer("https://ghe.corp/"); //$NON-NLS-1$
        assertEquals("ghe.corp", remote.host()); //$NON-NLS-1$
        assertEquals("https://ghe.corp/api/v3", remote.apiBase()); //$NON-NLS-1$
    }
}
