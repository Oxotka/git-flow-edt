package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.Test;

import dev.edt.gitflow.ui.views.MetadataType;

public class MetadataTypeTest
{
    private static final String[][] TYPES = {
        {"AccountingRegisters", "metadataAccountingRegister", "accounting_register"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"AccumulationRegisters", "metadataAccumulationRegister", "accumulation_register"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Bots", "metadataBot", "bot"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"BusinessProcesses", "metadataBusinessProcess", "business_process"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"CalculationRegisters", "metadataCalculationRegister", "calculation_register"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Catalogs", "metadataCatalog", "catalog"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"ChartsOfAccounts", "metadataChartOfAccounts", "chart_of_accounts"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"ChartsOfCalculationTypes", "metadataChartOfCalculationTypes", "chart_of_calculation_types"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"ChartsOfCharacteristicTypes", "metadataChartOfCharacteristicTypes", "chart_of_characteristic_types"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"CommandGroups", "metadataCommandGroup", "command_group"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"CommonAttributes", "metadataCommonAttribute", "common_attribute"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"CommonCommands", "metadataCommonCommand", "common_command"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"CommonForms", "metadataCommonForm", "common_form"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"CommonModules", "metadataCommonModule", "common_module"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"CommonPictures", "metadataCommonPicture", "common_picture"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"CommonTemplates", "metadataCommonTemplate", "common_template"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Configuration", "metadataConfiguration", "configuration"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Constants", "metadataConstant", "constant"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"DataProcessors", "metadataDataProcessor", "data_processor"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"DefinedTypes", "metadataDefinedType", "defined_type"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"DocumentJournals", "metadataDocumentJournal", "document_journal"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"DocumentNumerators", "metadataDocumentNumerator", "document_numerator"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Documents", "metadataDocument", "document"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Enums", "metadataEnum", "enum"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"EventSubscriptions", "metadataEventSubscription", "event_subscription"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"ExchangePlans", "metadataExchangePlan", "exchange_plan"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"ExternalDataSources", "metadataExternalDataSource", "external_data_source"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"FilterCriteria", "metadataFilterCriterion", "filter_criterion"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"FunctionalOptions", "metadataFunctionalOption", "functional_option"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"FunctionalOptionsParameters", "metadataFunctionalOptionParameter", "functional_option_parameter"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"HTTPServices", "metadataHttpService", "http"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"InformationRegisters", "metadataInformationRegister", "information_register"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"IntegrationServices", "metadataIntegrationService", "integration_service"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Languages", "metadataLanguage", "language"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"PaletteColors", "metadataPaletteColor", "palette_color"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Reports", "metadataReport", "report"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Roles", "metadataRole", "role"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"ScheduledJobs", "metadataScheduledJob", "scheduled_job"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Sequences", "metadataSequence", "sequence"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"SessionParameters", "metadataSessionParameter", "session_parameter"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"SettingsStorages", "metadataSettingsStorage", "settings_storage"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"StyleItems", "metadataStyleItem", "style_item"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Styles", "metadataStyle", "style"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Subsystems", "metadataSubsystem", "subsystem"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"Tasks", "metadataTask", "task"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"WebServices", "metadataWebService", "web_service"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"WebSocketClients", "metadataWebSocketClient", "web_socket_client"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"WSReferences", "metadataWsReference", "ws_reference"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"XDTOPackages", "metadataXdtoPackage", "xdto_package"} //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    @Test
    public void recognizedFoldersKeepTheirTypeAndIcon()
    {
        for (String[] expected : TYPES)
        {
            MetadataType type = MetadataType.forFolder(expected[0]);
            assertNotNull(expected[0], type);
            assertEquals(expected[0], expected[1], type.typeKey());
            assertEquals(expected[0], expected[2], type.iconName());
        }
    }

    @Test
    public void serversKeepTheirTextFallbackWithoutAnIcon()
    {
        MetadataType type = MetadataType.forFolder("Servers"); //$NON-NLS-1$
        assertNotNull(type);
        assertEquals("metadataServer", type.typeKey()); //$NON-NLS-1$
        assertNull(type.iconName());
    }

    @Test
    public void unknownFoldersAreNotRecognizedAsMetadata()
    {
        for (String folder : new String[] {"", "src", "UnknownObjects", "catalogs", "Catalogs/Products"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            assertNull(folder, MetadataType.forFolder(folder));
    }

    @Test
    public void recognizedTypesHaveEnglishAndRussianTextFallbacks()
    {
        for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.forLanguageTag("ru")}) //$NON-NLS-1$
        {
            ResourceBundle messages = ResourceBundle.getBundle("dev.edt.gitflow.ui.handlers.messages", //$NON-NLS-1$
                locale, MetadataType.class.getClassLoader());
            for (String[] expected : TYPES)
            {
                String key = MetadataType.forFolder(expected[0]).typeKey();
                assertTrue(key, messages.containsKey(key));
                assertTrue(key, !messages.getString(key).isBlank());
            }
            assertTrue(!messages.getString(MetadataType.forFolder("Servers").typeKey()).isBlank()); //$NON-NLS-1$
        }
    }
}
