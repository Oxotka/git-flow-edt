package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * Пути, которые изменились между двумя коммитами. Нужны, чтобы после переключения
 * ветки обновлять в рабочей области только затронутые каталоги, а не весь проект.
 */
public final class ChangedPaths
{
    private ChangedPaths()
    {
    }

    public static List<String> between(Repository repository, ObjectId before, ObjectId after) throws IOException
    {
        if (before == null || after == null || before.equals(after))
            return List.of();
        try (RevWalk walk = new RevWalk(repository))
        {
            RevCommit oldCommit = walk.parseCommit(before);
            RevCommit newCommit = walk.parseCommit(after);
            CanonicalTreeParser oldTree = tree(repository, oldCommit);
            CanonicalTreeParser newTree = tree(repository, newCommit);
            List<String> paths = new ArrayList<>();
            try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE))
            {
                formatter.setRepository(repository);
                formatter.setDetectRenames(false);
                for (DiffEntry entry : formatter.scan(oldTree, newTree))
                    paths.add(DiffEntry.DEV_NULL.equals(entry.getNewPath())
                        ? entry.getOldPath() : entry.getNewPath());
            }
            return paths;
        }
    }

    private static CanonicalTreeParser tree(Repository repository, RevCommit commit) throws IOException
    {
        try (ObjectReader reader = repository.newObjectReader())
        {
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, commit.getTree());
            return parser;
        }
    }
}
