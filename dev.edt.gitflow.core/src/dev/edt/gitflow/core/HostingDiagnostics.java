package dev.edt.gitflow.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Диагностический журнал операций хостинга в ядре: назначение проверяющего и
 * прочие необязательные шаги, ошибка которых не отменяет уже созданный запрос.
 * Пишется в домашний каталог пользователя, ошибки записи проглатываются.
 */
public final class HostingDiagnostics
{
    private static final String FILE_NAME = "gitflow-hosting.log"; //$NON-NLS-1$

    private HostingDiagnostics()
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
            // Журнал диагностический: если писать некуда, создание запроса не падает.
        }
    }
}
