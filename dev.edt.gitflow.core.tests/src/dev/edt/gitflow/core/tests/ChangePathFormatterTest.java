package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.Test;

import dev.edt.gitflow.ui.views.ChangePathFormatter;
import dev.edt.gitflow.ui.views.ChangePathFormatter.ChangeDisplay;

public class ChangePathFormatterTest
{
    @Test
    public void showsObjectsAndConfigurationWithTheirIcons()
    {
        var formatter = formatter(Locale.ENGLISH, true);
        assertEquals(new ChangeDisplay("Products", "catalog"), formatter.format("Catalogs/Products.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(new ChangeDisplay("Order", "document"), formatter.format("Documents/Order.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(new ChangeDisplay("Configuration", "configuration"), formatter.format("Configuration.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(new ChangeDisplay("User", "role"), formatter.format("Roles/User/Ext/Rights.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void normalizesWindowsPathsAndRemovesTheSourceRoot()
    {
        var formatter = formatter(Locale.ENGLISH, true);
        var expected = new ChangeDisplay("Products Object module", "catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expected, formatter.format("Catalogs/Products/Ext/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(expected, formatter.format("src/Catalogs/Products/Ext/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(expected, formatter.format("project/src/Catalogs/Products/Ext/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(expected, formatter.format("project\\src\\Catalogs\\Products\\Ext\\ObjectModule.bsl")); //$NON-NLS-1$
    }

    @Test
    public void distinguishesObjectAndServiceModules()
    {
        var formatter = formatter(Locale.ENGLISH, true);
        String[][] examples = {
            {"Catalogs/Products/Ext/ManagerModule.bsl", "Products Manager module", "catalog"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"Catalogs/Products/Ext/ObjectModule.bsl", "Products Object module", "catalog"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"InformationRegisters/Prices/Ext/RecordSetModule.bsl", "Prices Record set module", "information_register"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"Constants/Company/Ext/ValueManagerModule.bsl", "Company Value manager module", "constant"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"CommonModules/Tools/Ext/Module.bsl", "Tools Module", "common_module"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"CommonForms/Main/Ext/Form/Module.bsl", "Main Module", "common_form"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"HTTPServices/Api/Ext/Module.bsl", "Api Module", "http"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"WebServices/Api/Ext/Module.bsl", "Api Module", "web_service"} //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
        for (String[] example : examples)
            assertEquals(example[0], new ChangeDisplay(example[1], example[2]), formatter.format(example[0]));
    }

    @Test
    public void distinguishesFormsAndTheirModules()
    {
        var formatter = formatter(Locale.ENGLISH, true);
        assertEquals(new ChangeDisplay("Products Item Form", "catalog"), //$NON-NLS-1$ //$NON-NLS-2$
            formatter.format("Catalogs/Products/Forms/Item.xml")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Products Item Form", "catalog"), //$NON-NLS-1$ //$NON-NLS-2$
            formatter.format("Catalogs/Products/Forms/Item/Ext/Form.xml")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Products Item Form module", "catalog"), //$NON-NLS-1$ //$NON-NLS-2$
            formatter.format("Catalogs/Products/Forms/Item/Ext/Form/Module.bsl")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Main", "common_form"), formatter.format("CommonForms/Main/Ext/Form.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void addsEnglishTypeDescriptionsWhenIconsAreMissing()
    {
        var formatter = formatter(Locale.ENGLISH, false);
        assertEquals(new ChangeDisplay("Products Catalog", null), formatter.format("Catalogs/Products.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(new ChangeDisplay("Products Object module Catalog", null), //$NON-NLS-1$
            formatter.format("Catalogs/Products/Ext/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Configuration Configuration", null), formatter.format("Configuration.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(new ChangeDisplay("Products Item Form Catalog Form", null), //$NON-NLS-1$
            formatter.format("Catalogs/Products/Forms/Item.xml")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Service Item Form Form", null), //$NON-NLS-1$
            formatter.format("Servers/Service/Forms/Item.xml")); //$NON-NLS-1$
    }

    @Test
    public void usesRussianResourcesWithoutDependingOnTheDefaultLocale()
    {
        var formatter = formatter(Locale.forLanguageTag("ru"), false); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Товары Справочник", null), formatter.format("Catalogs/Товары.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(new ChangeDisplay("Товары Модуль объекта Справочник", null), //$NON-NLS-1$
            formatter.format("Catalogs/Товары/Ext/ObjectModule.bsl")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Товары Элемент Модуль формы Форма справочника", null), //$NON-NLS-1$
            formatter.format("Catalogs/Товары/Forms/Элемент/Ext/Form/Module.bsl")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Сервис Элемент Форма Форма", null), //$NON-NLS-1$
            formatter.format("Servers/Сервис/Forms/Элемент.xml")); //$NON-NLS-1$
    }

    @Test
    public void retainsTheOwningObjectForNestedFiles()
    {
        var formatter = formatter(Locale.ENGLISH, true);
        assertEquals(new ChangeDisplay("Products", "catalog"), //$NON-NLS-1$ //$NON-NLS-2$
            formatter.format("Catalogs/Products/Templates/Print/Ext/Template.xml")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Products", "catalog"), //$NON-NLS-1$ //$NON-NLS-2$
            formatter.format("Catalogs/Products/Commands/Open/Ext/CommandModule.bsl")); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Tools Module", "common_module"), //$NON-NLS-1$ //$NON-NLS-2$
            formatter.format("CommonModules/Tools/Ext/AnyFile.xml")); //$NON-NLS-1$
    }

    @Test
    public void keepsUnrecognizedPathsReadable()
    {
        var formatter = formatter(Locale.ENGLISH, false);
        for (String path : new String[] {"", "README.md", "Catalogs", "Unknown/Item.xml", "catalogs/Item.xml", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "my-src/Catalogs/Item.xml"}) //$NON-NLS-1$
            assertEquals(new ChangeDisplay(path, null), formatter.format(path));
        assertEquals(new ChangeDisplay("docs/guide.md", null), formatter.format("project\\src\\docs\\guide.md")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void fallsBackOnlyForTheUnavailableIcon()
    {
        ResourceBundle messages = messages(Locale.ENGLISH);
        var formatter = new ChangePathFormatter(messages, "catalog"::equals); //$NON-NLS-1$
        assertEquals(new ChangeDisplay("Products", "catalog"), formatter.format("Catalogs/Products.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(new ChangeDisplay("Order Document", null), formatter.format("Documents/Order.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(new ChangeDisplay("Service Server", null), formatter.format("Servers/Service.xml")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ChangePathFormatter formatter(Locale locale, boolean iconsAvailable)
    {
        return new ChangePathFormatter(messages(locale), name -> name != null && iconsAvailable);
    }

    private static ResourceBundle messages(Locale locale)
    {
        return ResourceBundle.getBundle("dev.edt.gitflow.ui.handlers.messages", locale, //$NON-NLS-1$
            ChangePathFormatter.class.getClassLoader());
    }
}
