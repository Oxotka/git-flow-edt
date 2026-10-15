package dev.edt.gitflow.ui.views;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Resource lookup cache for one view, including resources that are absent. */
public final class MetadataIconLookup
{
    private final Function<String, URL> resolver;
    private final Map<String, URL> resources = new HashMap<>();

    public MetadataIconLookup(Function<String, URL> resolver)
    {
        this.resolver = resolver;
    }

    public URL find(String name)
    {
        if (name == null)
            return null;
        if (!resources.containsKey(name))
            resources.put(name, resolver.apply(name));
        return resources.get(name);
    }

    public void clear()
    {
        resources.clear();
    }
}
