package dev.edt.gitflow.ui.views;

import java.util.ResourceBundle;
import java.util.function.Predicate;

public final class ChangePathFormatter
{
    public record ChangeDisplay(String text, String icon)
    {
    }

    private final ResourceBundle messages;
    private final Predicate<String> iconAvailable;

    public ChangePathFormatter(ResourceBundle messages, Predicate<String> iconAvailable)
    {
        this.messages = messages;
        this.iconAvailable = iconAvailable;
    }

    public ChangeDisplay format(String path)
    {
        String relative = path.replace('\\', '/'); //$NON-NLS-1$
        int sourceRoot = ("/" + relative).indexOf("/src/"); //$NON-NLS-1$ //$NON-NLS-2$
        if (sourceRoot >= 0)
            relative = relative.substring(sourceRoot + "src/".length()); //$NON-NLS-1$

        if ("Configuration.xml".equals(relative)) //$NON-NLS-1$
            return namedChange("Configuration", "metadataConfiguration", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String[] parts = relative.split("/"); //$NON-NLS-1$
        if (parts.length < 2)
            return new ChangeDisplay(relative, null);

        MetadataType type = MetadataType.forFolder(parts[0]);
        if (type == null)
            return new ChangeDisplay(relative, null);

        String objectType = type.typeKey();
        String objectName = parts[1];
        String fileName = parts[parts.length - 1];
        String icon = type.iconName();
        if ("CommonModules".equals(parts[0]) && parts.length >= 3) //$NON-NLS-1$
            return componentChange(objectName, "partModule", objectType, icon); //$NON-NLS-1$
        if ("Roles".equals(parts[0]) && "Rights.xml".equals(fileName) && parts.length >= 3) //$NON-NLS-1$ //$NON-NLS-2$
            return namedChange(objectName, objectType, icon);

        int forms = -1;
        for (int i = 2; i < parts.length; i++)
            if ("Forms".equals(parts[i])) //$NON-NLS-1$
            {
                forms = i;
                break;
            }
        boolean formChange = forms >= 0 && forms + 1 < parts.length;
        if (formChange)
        {
            String formIcon = !iconAvailable.test(icon) ? null : icon;
            String formType = "metadataForm" + objectType.substring("metadata".length()); //$NON-NLS-1$
            String formPart = "Module.bsl".equals(fileName) ? "partFormModule" : "partForm"; //$NON-NLS-1$ //$NON-NLS-2$
            String label = objectName + " " + stripExtension(parts[forms + 1]) //$NON-NLS-1$
                + " " + messages.getString(formPart); //$NON-NLS-1$
            if (formIcon == null)
                label += " " + messages.getString(messages.containsKey(formType) ? formType : "metadataForm"); //$NON-NLS-1$
            return new ChangeDisplay(label, formIcon);
        }
        if ("ManagerModule.bsl".equals(fileName)) //$NON-NLS-1$
            return componentChange(objectName, "partManagerModule", objectType, icon); //$NON-NLS-1$
        if ("ObjectModule.bsl".equals(fileName)) //$NON-NLS-1$
            return componentChange(objectName, "partObjectModule", objectType, icon); //$NON-NLS-1$
        if ("RecordSetModule.bsl".equals(fileName)) //$NON-NLS-1$
            return componentChange(objectName, "partRecordSetModule", objectType, icon); //$NON-NLS-1$
        if ("ValueManagerModule.bsl".equals(fileName)) //$NON-NLS-1$
            return componentChange(objectName, "partValueManagerModule", objectType, icon); //$NON-NLS-1$
        if ("Module.bsl".equals(fileName) //$NON-NLS-1$
            && ("metadataCommonModule".equals(objectType) || "metadataCommonForm".equals(objectType) //$NON-NLS-1$ //$NON-NLS-2$
                || "metadataHttpService".equals(objectType) || "metadataWebService".equals(objectType)) //$NON-NLS-1$ //$NON-NLS-2$
            )
            return componentChange(objectName, "partModule", objectType, icon); //$NON-NLS-1$
        if ("metadataCommonForm".equals(objectType) && "Form.xml".equals(fileName) //$NON-NLS-1$ //$NON-NLS-2$
            && containsPath(parts, "Ext", "Form.xml")) //$NON-NLS-1$ //$NON-NLS-2$
            return namedChange(objectName, objectType, icon);
        if (parts.length == 2 && fileName.endsWith(".xml")) //$NON-NLS-1$
            return namedChange(stripExtension(objectName), objectType, icon);
        // Files nested under a metadata object (templates, commands, layouts, etc.)
        // still belong to that object. Showing the repository path exposes folders
        // such as Catalogs and makes otherwise recognized metadata look inconsistent.
        return namedChange(objectName, objectType, icon);
    }

    private ChangeDisplay namedChange(String name, String typeKey, String icon)
    {
        String usableIcon = !iconAvailable.test(icon) ? null : icon;
        return new ChangeDisplay(usableIcon == null ? name + " " + messages.getString(typeKey) : name, usableIcon);
    }

    private ChangeDisplay componentChange(String name, String componentKey, String typeKey, String icon)
    {
        String usableIcon = !iconAvailable.test(icon) ? null : icon;
        String label = componentKey.isEmpty() ? name : name + " " + messages.getString(componentKey); //$NON-NLS-1$
        if (usableIcon == null)
            label += " " + messages.getString(typeKey); //$NON-NLS-1$
        return new ChangeDisplay(label, usableIcon);
    }

    private static String stripExtension(String name)
    {
        int extension = name.lastIndexOf('.'); //$NON-NLS-1$
        return extension < 0 ? name : name.substring(0, extension);
    }

    private static boolean containsPath(String[] parts, String... expected)
    {
        if (parts.length < expected.length)
            return false;
        for (int start = 0; start <= parts.length - expected.length; start++)
        {
            boolean match = true;
            for (int i = 0; i < expected.length; i++)
                if (!expected[i].equals(parts[start + i]))
                {
                    match = false;
                    break;
                }
            if (match)
                return true;
        }
        return false;
    }

}
