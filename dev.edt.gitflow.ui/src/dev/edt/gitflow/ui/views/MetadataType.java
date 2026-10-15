package dev.edt.gitflow.ui.views;

import java.util.Map;

public record MetadataType(String typeKey, String iconName)
{
    private static final Map<String, MetadataType> TYPES = Map.ofEntries(
        Map.entry("AccountingRegisters", new MetadataType("metadataAccountingRegister", "accounting_register")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("AccumulationRegisters", new MetadataType("metadataAccumulationRegister", "accumulation_register")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("BusinessProcesses", new MetadataType("metadataBusinessProcess", "business_process")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Bots", new MetadataType("metadataBot", "bot")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Catalogs", new MetadataType("metadataCatalog", "catalog")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("ChartsOfAccounts", new MetadataType("metadataChartOfAccounts", "chart_of_accounts")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("ChartsOfCalculationTypes", new MetadataType("metadataChartOfCalculationTypes", "chart_of_calculation_types")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("ChartsOfCharacteristicTypes", new MetadataType("metadataChartOfCharacteristicTypes", "chart_of_characteristic_types")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("CommandGroups", new MetadataType("metadataCommandGroup", "command_group")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("CommonAttributes", new MetadataType("metadataCommonAttribute", "common_attribute")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("CommonCommands", new MetadataType("metadataCommonCommand", "common_command")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("CommonForms", new MetadataType("metadataCommonForm", "common_form")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("CommonModules", new MetadataType("metadataCommonModule", "common_module")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("CommonPictures", new MetadataType("metadataCommonPicture", "common_picture")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("CommonTemplates", new MetadataType("metadataCommonTemplate", "common_template")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Constants", new MetadataType("metadataConstant", "constant")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Configuration", new MetadataType("metadataConfiguration", "configuration")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("DataProcessors", new MetadataType("metadataDataProcessor", "data_processor")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("DefinedTypes", new MetadataType("metadataDefinedType", "defined_type")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("DocumentJournals", new MetadataType("metadataDocumentJournal", "document_journal")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("DocumentNumerators", new MetadataType("metadataDocumentNumerator", "document_numerator")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Documents", new MetadataType("metadataDocument", "document")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Enums", new MetadataType("metadataEnum", "enum")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("EventSubscriptions", new MetadataType("metadataEventSubscription", "event_subscription")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("ExchangePlans", new MetadataType("metadataExchangePlan", "exchange_plan")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("ExternalDataSources", new MetadataType("metadataExternalDataSource", "external_data_source")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("FilterCriteria", new MetadataType("metadataFilterCriterion", "filter_criterion")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("FunctionalOptions", new MetadataType("metadataFunctionalOption", "functional_option")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("FunctionalOptionsParameters", new MetadataType("metadataFunctionalOptionParameter", "functional_option_parameter")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("HTTPServices", new MetadataType("metadataHttpService", "http")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("InformationRegisters", new MetadataType("metadataInformationRegister", "information_register")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("IntegrationServices", new MetadataType("metadataIntegrationService", "integration_service")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Languages", new MetadataType("metadataLanguage", "language")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Roles", new MetadataType("metadataRole", "role")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("ScheduledJobs", new MetadataType("metadataScheduledJob", "scheduled_job")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Sequences", new MetadataType("metadataSequence", "sequence")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Servers", new MetadataType("metadataServer", null)), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("SessionParameters", new MetadataType("metadataSessionParameter", "session_parameter")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("SettingsStorages", new MetadataType("metadataSettingsStorage", "settings_storage")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("StyleItems", new MetadataType("metadataStyleItem", "style_item")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Styles", new MetadataType("metadataStyle", "style")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Subsystems", new MetadataType("metadataSubsystem", "subsystem")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Tasks", new MetadataType("metadataTask", "task")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("PaletteColors", new MetadataType("metadataPaletteColor", "palette_color")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("XDTOPackages", new MetadataType("metadataXdtoPackage", "xdto_package")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("WebServices", new MetadataType("metadataWebService", "web_service")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("WebSocketClients", new MetadataType("metadataWebSocketClient", "web_socket_client")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("WSReferences", new MetadataType("metadataWsReference", "ws_reference")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("Reports", new MetadataType("metadataReport", "report")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map.entry("CalculationRegisters", new MetadataType("metadataCalculationRegister", "calculation_register"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    public static MetadataType forFolder(String folder)
    {
        return TYPES.get(folder);
    }
}
