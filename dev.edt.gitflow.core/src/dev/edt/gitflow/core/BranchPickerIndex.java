package dev.edt.gitflow.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BranchPickerIndex
{
    public enum Group { LOCAL, REMOTE, TAG }

    public record Ref(String name, String ref, Group group, int lastActivity)
    {
    }

    private record IndexedRef(Ref ref, String searchName, String remoteName, List<String> pathParts,
        List<String> folders)
    {
    }

    private static final Comparator<IndexedRef> ACTIVITY_ORDER = Comparator
        .comparingInt((IndexedRef entry) -> entry.ref().lastActivity()).reversed()
        .thenComparing(entry -> entry.ref().name());

    private final Map<Group, List<IndexedRef>> refsByGroup = new EnumMap<>(Group.class);
    private final Map<Group, Set<String>> folderSets = new EnumMap<>(Group.class);
    private final Map<Group, List<String>> orderedFolders = new EnumMap<>(Group.class);
    private final Map<String, List<String>> folderPartsByPath = new HashMap<>();
    private final Map<String, List<IndexedRef>> refsByRemote = new HashMap<>();
    private final Map<String, IndexedRef> refsByName = new LinkedHashMap<>();
    private final Set<String> pinnedFolders = new HashSet<>();
    private List<String> orderedRemoteNames = List.of();

    public BranchPickerIndex()
    {
        for (Group group : Group.values())
        {
            refsByGroup.put(group, new ArrayList<>());
            folderSets.put(group, new LinkedHashSet<>());
            orderedFolders.put(group, List.of());
        }
    }

    public void addAll(Collection<Ref> refs)
    {
        Set<Group> changedGroups = new HashSet<>();
        boolean remotesChanged = false;
        for (Ref ref : refs)
        {
            if (refsByName.containsKey(ref.ref()))
                continue;
            String[] parts = ref.name().split("/"); //$NON-NLS-1$
            String remoteName = ref.group() == Group.REMOTE && parts.length > 0 ? parts[0] : null;
            List<String> folders = folderPaths(parts, ref.group(), remoteName);
            IndexedRef indexed = new IndexedRef(ref, ref.name().toLowerCase(Locale.ROOT), remoteName,
                List.of(parts), folders);
            refsByName.put(ref.ref(), indexed);
            refsByGroup.get(ref.group()).add(indexed);
            if (remoteName != null)
                refsByRemote.computeIfAbsent(remoteName, ignored -> new ArrayList<>()).add(indexed);
            folderSets.get(ref.group()).addAll(folders);
            for (String folder : folders)
                folderPartsByPath.computeIfAbsent(folder, BranchPickerIndex::splitPath);
            changedGroups.add(ref.group());
            remotesChanged |= ref.group() == Group.REMOTE;
        }
        for (Group group : changedGroups)
        {
            refsByGroup.get(group).sort(ACTIVITY_ORDER);
            refreshFolderOrder(group);
        }
        if (remotesChanged)
        {
            refsByRemote.values().forEach(remoteRefs -> remoteRefs.sort(ACTIVITY_ORDER));
            orderedRemoteNames = refsByRemote.keySet().stream()
                .sorted(Comparator.comparing((String name) -> !name.equals("origin")) //$NON-NLS-1$
                    .thenComparing(Comparator.naturalOrder())).toList();
        }
    }

    public void clear()
    {
        refsByName.clear();
        refsByRemote.clear();
        orderedRemoteNames = List.of();
        for (Group group : Group.values())
        {
            refsByGroup.get(group).clear();
            folderSets.get(group).clear();
            orderedFolders.put(group, List.of());
        }
        folderPartsByPath.clear();
    }

    public void setPinnedFolders(Collection<String> folders)
    {
        pinnedFolders.clear();
        pinnedFolders.addAll(folders);
        for (Group group : Group.values())
            refreshFolderOrder(group);
    }

    public List<Ref> all()
    {
        return refsByName.values().stream().map(IndexedRef::ref).toList();
    }

    public List<Ref> filter(Group group, String query)
    {
        String foldedQuery = fold(query);
        return refsByGroup.get(group).stream()
            .filter(entry -> entry.searchName().contains(foldedQuery))
            .map(IndexedRef::ref).toList();
    }

    public List<String> remoteNames(String query)
    {
        String foldedQuery = fold(query);
        Set<String> matches = new HashSet<>();
        refsByRemote.forEach((remote, refs) ->
        {
            if (refs.stream().anyMatch(entry -> entry.searchName().contains(foldedQuery)))
                matches.add(remote);
        });
        return orderedRemoteNames.stream().filter(matches::contains).toList();
    }

    public List<Ref> filterRemote(String query, String remoteName)
    {
        String foldedQuery = fold(query);
        return refsByRemote.getOrDefault(remoteName, List.of()).stream()
            .filter(entry -> entry.searchName().contains(foldedQuery))
            .map(IndexedRef::ref).toList();
    }

    public List<String> folderPaths(Group group, String query, String remoteName)
    {
        String foldedQuery = fold(query);
        Set<String> visible = new HashSet<>();
        List<IndexedRef> candidates = group == Group.REMOTE && remoteName != null
            ? refsByRemote.getOrDefault(remoteName, List.of()) : refsByGroup.get(group);
        for (IndexedRef entry : candidates)
        {
            if (!entry.searchName().contains(foldedQuery))
                continue;
            visible.addAll(entry.folders());
        }
        return orderedFolders.get(group).stream().filter(visible::contains).toList();
    }

    public String remoteName(Ref ref)
    {
        return remoteName(ref.ref());
    }

    public String remoteName(String ref)
    {
        IndexedRef indexed = refsByName.get(ref);
        return indexed == null ? null : indexed.remoteName();
    }

    public List<String> pathParts(Ref ref)
    {
        return pathParts(ref.ref());
    }

    public List<String> pathParts(String ref)
    {
        IndexedRef indexed = refsByName.get(ref);
        if (indexed == null || indexed.ref().group() != Group.REMOTE)
            return indexed == null ? List.of() : indexed.pathParts();
        return indexed.pathParts().subList(1, indexed.pathParts().size());
    }

    public List<String> folderParts(String path)
    {
        return folderPartsByPath.getOrDefault(path, List.of());
    }

    private List<String> folderPaths(String[] parts, Group group, String remoteName)
    {
        List<String> result = new ArrayList<>();
        String path = ""; //$NON-NLS-1$
        int start = group == Group.REMOTE ? 1 : 0;
        if (group == Group.REMOTE)
            path = remoteName;
        for (int i = start; i < parts.length - 1; i++)
        {
            path = path.isEmpty() ? parts[i] : path + "/" + parts[i]; //$NON-NLS-1$
            result.add(path);
        }
        return List.copyOf(result);
    }

    private void refreshFolderOrder(Group group)
    {
        boolean remote = group == Group.REMOTE;
        orderedFolders.put(group, folderSets.get(group).stream()
            .sorted(Comparator.comparingInt(BranchPickerIndex::pathDepth)
                .thenComparing(path -> remote && pinnedFolders.contains(path) ? 0 : 1)
                .thenComparing(Comparator.naturalOrder())).toList());
    }

    private static String fold(String value)
    {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> splitPath(String path)
    {
        return List.of(path.split("/")); //$NON-NLS-1$
    }

    private static int pathDepth(String path)
    {
        return (int) path.chars().filter(character -> character == '/').count();
    }
}
