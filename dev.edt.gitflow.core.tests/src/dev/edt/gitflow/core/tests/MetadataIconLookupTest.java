package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dev.edt.gitflow.ui.views.MetadataIconLookup;

public class MetadataIconLookupTest
{
    @Test
    public void missingIconIsLookedUpOnlyOnce()
    {
        AtomicInteger calls = new AtomicInteger();
        var lookup = new MetadataIconLookup(name -> { calls.incrementAndGet(); return null; });
        for (int i = 0; i < 100; i++)
            assertNull(lookup.find("missing")); //$NON-NLS-1$
        assertEquals(1, calls.get());
    }

    @Test
    public void availableIconKeepsItsResourceUrl() throws Exception
    {
        URL resource = URI.create("file:/icons/catalog.png").toURL(); //$NON-NLS-1$
        AtomicInteger calls = new AtomicInteger();
        var lookup = new MetadataIconLookup(name -> { calls.incrementAndGet(); return resource; });
        for (int i = 0; i < 100; i++)
            assertEquals(resource, lookup.find("catalog")); //$NON-NLS-1$
        assertEquals(1, calls.get());
    }

    @Test
    public void nullIconDoesNotQueryResources()
    {
        AtomicInteger calls = new AtomicInteger();
        var lookup = new MetadataIconLookup(name -> { calls.incrementAndGet(); return null; });
        assertNull(lookup.find(null));
        assertEquals(0, calls.get());
    }

    @Test
    public void clearingCacheAllowsPreviouslyMissingResourceToAppear() throws Exception
    {
        URL resource = URI.create("file:/icons/catalog.png").toURL(); //$NON-NLS-1$
        AtomicInteger calls = new AtomicInteger();
        var lookup = new MetadataIconLookup(name -> calls.incrementAndGet() == 1 ? null : resource);
        assertNull(lookup.find("catalog")); //$NON-NLS-1$
        assertNull(lookup.find("catalog")); //$NON-NLS-1$
        lookup.clear();
        assertEquals(resource, lookup.find("catalog")); //$NON-NLS-1$
        assertEquals(2, calls.get());
    }
}
