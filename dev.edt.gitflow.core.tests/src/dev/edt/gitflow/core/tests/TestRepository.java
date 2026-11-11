package dev.edt.gitflow.core.tests;

import java.io.File;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;

/**
 * Создает временный репозиторий для тестов с настройками, не зависящими от
 * машины. Глобальный core.autocrlf=true иначе превращает переводы строк при
 * checkout, и тесты начинают сравнивать разные файлы.
 */
public final class TestRepository
{
    private TestRepository()
    {
    }

    public static Git init(Path directory) throws Exception
    {
        return init(directory.toFile());
    }

    public static Git init(File directory) throws Exception
    {
        Git git = Git.init().setDirectory(directory).call();
        disableLineEndingConversion(git);
        return git;
    }

    public static Git init(File directory, String initialBranch) throws Exception
    {
        Git git = Git.init().setInitialBranch(initialBranch).setDirectory(directory).call();
        disableLineEndingConversion(git);
        return git;
    }

    private static void disableLineEndingConversion(Git git) throws Exception
    {
        var config = git.getRepository().getConfig();
        config.setBoolean("core", null, "autocrlf", false); //$NON-NLS-1$ //$NON-NLS-2$
        config.setString("core", null, "eol", "lf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        config.save();
    }
}
