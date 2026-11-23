package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.edt.gitflow.core.GitLabRemote;

public class GitLabRemoteTest
{
    @Test
    public void prefillsTokenOnConfiguredServer()
    {
        var remote = GitLabRemote.forServer("https://git.example:8443");
        assertEquals("https://git.example:8443/-/user_settings/personal_access_tokens"
            + "?name=EDT%20Git%20Flow&scopes=api&description=MR+%26+API", remote.tokenCreationUrl("MR & API"));
    }

    @Test
    public void parsesHttpsRemote()
    {
        var remote = GitLabRemote.parse("https://akeso.dept07/BP/npo.git"); //$NON-NLS-1$
        assertEquals("akeso.dept07", remote.host()); //$NON-NLS-1$
        assertEquals("BP/npo", remote.projectPath()); //$NON-NLS-1$
        assertEquals("https://akeso.dept07/api/v4", remote.apiBase()); //$NON-NLS-1$
        assertEquals("https://akeso.dept07/BP/npo", remote.webBase()); //$NON-NLS-1$
    }

    @Test
    public void dropsSshPortFromWebAddress()
    {
        var remote = GitLabRemote.parse("ssh://git@git.example.com:2222/group/sub/project.git"); //$NON-NLS-1$
        assertEquals("git.example.com", remote.host()); //$NON-NLS-1$
        assertEquals("group/sub/project", remote.projectPath()); //$NON-NLS-1$
        assertEquals("https://git.example.com/api/v4", remote.apiBase()); //$NON-NLS-1$
    }

    @Test
    public void parsesScpLikeRemote()
    {
        var remote = GitLabRemote.parse("git@host:group/project.git"); //$NON-NLS-1$
        assertEquals("host", remote.host()); //$NON-NLS-1$
        assertEquals("group/project", remote.projectPath()); //$NON-NLS-1$
    }

    @Test
    public void buildsApiBaseFromServerRoot()
    {
        var remote = GitLabRemote.forServer("https://akeso.dept07/"); //$NON-NLS-1$
        assertEquals("akeso.dept07", remote.host()); //$NON-NLS-1$
        assertEquals("https://akeso.dept07/api/v4", remote.apiBase()); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRemoteWithoutHost()
    {
        GitLabRemote.parse("C:/repos/local"); //$NON-NLS-1$
    }
}
