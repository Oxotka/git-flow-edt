package dev.edt.gitflow.ui.handlers;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Диагностический журнал подключения к хостингу. Пишется в файл в домашнем
 * каталоге пользователя: по нему видно, что именно произошло при проверке.
 * Имя файла оставлено прежним, чтобы журнал GitLab оставался на месте.
 */
public final class HostingLog
{
    private static final String FILE_NAME = "gitflow-gitlab.log"; //$NON-NLS-1$

    private HostingLog()
    {
    }

    public static Path file()
    {
        return Path.of(System.getProperty("user.home"), FILE_NAME); //$NON-NLS-1$
    }

    public static void write(String message)
    {
        write(message, null);
    }

    public static void write(String message, Throwable error)
    {
        StringBuilder text = new StringBuilder();
        text.append(LocalDateTime.now()).append(' ').append(message).append(System.lineSeparator());
        if (error != null)
        {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            text.append(trace);
            text.append(System.lineSeparator());
        }
        try
        {
            Files.writeString(file(), text.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            // Журнал диагностический: если писать некуда, проверка не должна падать.
        }
    }

    public static boolean exists()
    {
        File file = file().toFile();
        return file.isFile() && file.length() > 0;
    }
}
