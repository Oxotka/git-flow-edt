package dev.edt.gitflow.ui.handlers;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.core.commands.State;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.DecorationOverlayIcon;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Color;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ListDialog;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;
import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.BranchOperations;
import dev.edt.gitflow.core.BranchPickerIndex;
import dev.edt.gitflow.core.PullOperations;

final class BranchPicker
{
    enum Kind { BRANCH, TAG }

    private enum SortOrder { ACTIVITY_NEWEST_FIRST, ACTIVITY_OLDEST_FIRST, NAME_ASCENDING, NAME_DESCENDING }

    private enum BranchFilter { ACTIVE, ALL, CLOSED_OR_MERGED }

    private record Folder(String path, boolean remote)
    {
    }

    record Choice(String name, String ref, boolean remote, boolean localExists, int lastActivity,
        String author, String commitSubject, Kind kind, boolean upstreamGone, String mergedInto)
    {
        String localName()
        {
            return remote ? name.substring(name.indexOf('/') + 1) : name;
        }
    }

    private final Composite panel;
    private final Repository repository;
    private final boolean includeRemote;
    private final Tree tree;
    private final Button favoriteButton;
    private final Menu settingsMenu;
    private final MenuItem activityNewestItem;
    private final MenuItem activityOldestItem;
    private final MenuItem nameAscendingItem;
    private final MenuItem nameDescendingItem;
    private final MenuItem hierarchyItem;
    private final Text search;
    private final Image branchImage;
    private final Image currentBranchImage;
    private final Image tagImage;
    private final Font smallFont;
    private final List<Choice> branches = new ArrayList<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<Choice> favoriteChoices = new ArrayList<>();
    private final Set<String> pinnedFolders = new LinkedHashSet<>();
    private final Preferences preferences;
    private final Consumer<Choice> onSelect;
    private final Runnable onActivate;
    private String currentBranch;
    private final boolean includeTags;
    private Choice selected;
    private final BranchPickerIndex branchIndex = new BranchPickerIndex();
    private final Map<String, Choice> choicesByRef = new HashMap<>();
    private Runnable pendingSearchRebuild;
    private boolean localLoading = true;
    private boolean remoteLoading;
    private boolean tagsLoading;
    private SortOrder sortOrder = SortOrder.ACTIVITY_NEWEST_FIRST;
    private String localLoadError;
    private String remoteLoadError;
    private String tagLoadError;
    private final Menu contextMenu;
    private final boolean filterLocalBranches;
    private List<String> contextBranches = List.of();
    private BranchFilter branchFilter = BranchFilter.ACTIVE;
    private String selectedMergeTarget;

    BranchPicker(Composite parent, Repository repository, boolean includeRemote,
        Consumer<Choice> onSelect, Runnable onActivate) throws IOException
    {
        this(parent, repository, includeRemote, false, onSelect, onActivate);
    }

    BranchPicker(Composite parent, Repository repository, boolean includeRemote, boolean includeTags,
        Consumer<Choice> onSelect, Runnable onActivate) throws IOException
    {
        this(parent, repository, includeRemote, includeTags, false, onSelect, onActivate);
    }

    BranchPicker(Composite parent, Repository repository, boolean includeRemote, boolean includeTags,
        boolean filterLocalBranches, Consumer<Choice> onSelect, Runnable onActivate) throws IOException
    {
        this.onSelect = onSelect;
        this.filterLocalBranches = filterLocalBranches;
        this.onActivate = onActivate;
        this.repository = repository;
        this.includeRemote = includeRemote;
        this.includeTags = includeTags;
        remoteLoading = includeRemote;
        tagsLoading = includeTags;
        currentBranch = repository.getBranch();
        BranchHierarchySettings.initialize();
        String id = UUID.nameUUIDFromBytes(repository.getDirectory().getAbsolutePath()
            .getBytes(StandardCharsets.UTF_8)).toString();
        preferences = InstanceScope.INSTANCE.getNode("dev.edt.gitflow.ui").node("favorites").node(id); //$NON-NLS-1$ //$NON-NLS-2$
        String saved = preferences.get("refs", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (!saved.isEmpty())
            favorites.addAll(List.of(saved.split("\n"))); //$NON-NLS-1$
        String savedFolders = preferences.get("folders", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (!savedFolders.isEmpty())
            pinnedFolders.addAll(List.of(savedFolders.split("\n"))); //$NON-NLS-1$
        branchIndex.setPinnedFolders(pinnedFolders);
        panel = new Composite(parent, SWT.NONE);
        int columns = filterLocalBranches ? 4 : 3;
        panel.setLayout(new GridLayout(columns, false));
        GridData panelData = new GridData(SWT.FILL, SWT.FILL, true, true);
        panelData.horizontalSpan = 2;
        panel.setLayoutData(panelData);
        search = new Text(panel, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        search.setMessage(Messages.get("searchBranches")); //$NON-NLS-1$
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (filterLocalBranches)
        {
            Combo filter = new Combo(panel, SWT.DROP_DOWN | SWT.READ_ONLY);
            filter.setItems(Messages.get("activeBranches"), Messages.get("allBranches"),
                Messages.get("goneBranches"));
            filter.select(0);
            filter.addListener(SWT.Selection, event ->
            {
                branchFilter = BranchFilter.values()[filter.getSelectionIndex()];
                rebuild();
            });
        }
        favoriteButton = new Button(panel, SWT.PUSH);
        favoriteButton.setText(Messages.get("removeFavorite")); //$NON-NLS-1$
        int favoriteWidth = favoriteButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
        favoriteButton.setText(Messages.get("addFavorite")); //$NON-NLS-1$
        GridData favoriteData = new GridData(SWT.FILL, SWT.CENTER, false, false);
        favoriteData.widthHint = Math.max(favoriteWidth,
            favoriteButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
        favoriteButton.setLayoutData(favoriteData);
        favoriteButton.setEnabled(false);
        Button settingsButton = new Button(panel, SWT.PUSH);
        settingsButton.setText("⚙"); //$NON-NLS-1$
        settingsButton.setToolTipText(Messages.get("branchPickerSettings")); //$NON-NLS-1$
        settingsButton.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        settingsMenu = new Menu(panel.getShell(), SWT.POP_UP);
        activityNewestItem = radioMenuItem("sortActivityNewestFirst"); //$NON-NLS-1$
        activityOldestItem = radioMenuItem("sortActivityOldestFirst"); //$NON-NLS-1$
        nameAscendingItem = radioMenuItem("sortBranchesAscending"); //$NON-NLS-1$
        nameDescendingItem = radioMenuItem("sortBranchesDescending"); //$NON-NLS-1$
        new MenuItem(settingsMenu, SWT.SEPARATOR);
        hierarchyItem = new MenuItem(settingsMenu, SWT.CHECK);
        hierarchyItem.setText(Messages.get("branchHierarchyGlobal")); //$NON-NLS-1$
        activityNewestItem.addListener(SWT.Selection, event -> chooseSortOrder(activityNewestItem,
            SortOrder.ACTIVITY_NEWEST_FIRST));
        activityOldestItem.addListener(SWT.Selection, event -> chooseSortOrder(activityOldestItem,
            SortOrder.ACTIVITY_OLDEST_FIRST));
        nameAscendingItem.addListener(SWT.Selection, event -> chooseSortOrder(nameAscendingItem,
            SortOrder.NAME_ASCENDING));
        nameDescendingItem.addListener(SWT.Selection, event -> chooseSortOrder(nameDescendingItem,
            SortOrder.NAME_DESCENDING));
        hierarchyItem.addListener(SWT.Selection, event -> setHierarchyEnabled(hierarchyItem.getSelection()));
        if (filterLocalBranches)
        {
            new MenuItem(settingsMenu, SWT.SEPARATOR);
            MenuItem mergeTarget = menuItem(settingsMenu, Messages.get("branchMergeTarget"), this::chooseMergeTarget);
            settingsMenu.addListener(SWT.Show, event -> mergeTarget.setEnabled(!localLoading && !remoteLoading
                && !tagsLoading));
        }
        settingsMenu.addListener(SWT.Show, event -> updateSettingsMenu());
        settingsButton.addListener(SWT.Selection, event ->
        {
            Point location = settingsButton.toDisplay(0, settingsButton.getSize().y);
            settingsMenu.setLocation(location);
            settingsMenu.setVisible(true);
        });
        panel.addListener(SWT.Dispose, event -> settingsMenu.dispose());
        int selectionStyle = filterLocalBranches ? SWT.MULTI : SWT.SINGLE;
        tree = new Tree(panel, SWT.BORDER | selectionStyle | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true, columns, 1);
        treeData.heightHint = 220;
        treeData.widthHint = 680;
        tree.setLayoutData(treeData);
        tree.setHeaderVisible(false);
        tree.setLinesVisible(false);
        FontData[] smallData = tree.getFont().getFontData();
        for (FontData data : smallData)
            data.setHeight(Math.max(8, data.getHeight() - 1));
        smallFont = new Font(tree.getDisplay(), smallData);
        ImageDescriptor branchDescriptor = egitImage("icons/obj16/branch_obj.png"); //$NON-NLS-1$
        branchImage = branchDescriptor.createImage();
        ImageDescriptor checkedOverlay = egitImage("icons/ovr/checkedout_ov.png"); //$NON-NLS-1$
        currentBranchImage = new DecorationOverlayIcon(branchDescriptor, checkedOverlay,
            IDecoration.BOTTOM_LEFT).createImage();
        tagImage = egitImage("icons/obj16/tags.png").createImage(); //$NON-NLS-1$
        tree.addListener(SWT.Dispose, event ->
        {
            branchImage.dispose();
            currentBranchImage.dispose();
            tagImage.dispose();
            smallFont.dispose();
        });
        tree.addListener(SWT.EraseItem, event ->
        {
            if (event.index == 0 && event.item.getData() instanceof Choice)
                event.detail &= ~SWT.FOREGROUND;
        });
        tree.addListener(SWT.PaintItem, event ->
        {
            if (event.index != 0 || !(event.item instanceof TreeItem item)
                || !(item.getData() instanceof Choice choice))
                return;
            String label = (String) item.getData("label"); //$NON-NLS-1$
            GC gc = event.gc;
            Font originalFont = gc.getFont();
            Color originalColor = gc.getForeground();
            boolean selectedRow = (event.detail & SWT.SELECTED) != 0;
            boolean lightWindowsSelection = selectedRow && "win32".equals(SWT.getPlatform()); //$NON-NLS-1$
            boolean activeSelection = selectedRow && tree.isFocusControl() && !lightWindowsSelection;
            Color primary = activeSelection
                ? tree.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT) : tree.getForeground();
            Color secondary = activeSelection ? primary : tree.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
            try
            {
                Image icon = item.getImage(0);
                if (icon != null)
                {
                    var bounds = item.getImageBounds(0);
                    gc.drawImage(icon, bounds.x, bounds.y);
                }
                int x = item.getTextBounds(0).x;
                x = paintPart(gc, label + "  ", originalFont, primary, x, event.y, event.height); //$NON-NLS-1$
                String status = branchStatus(choice);
                if (!status.isEmpty())
                    x = paintPart(gc, status + "  ·  ", smallFont, secondary, x, event.y, event.height);
                if (choice.lastActivity() > 0)
                    x = paintPart(gc, relativeTime(choice.lastActivity()) + "  ·  ", //$NON-NLS-1$
                        smallFont, secondary, x, event.y, event.height);
                if (!choice.author().isEmpty())
                    x = paintPart(gc, choice.author() + "  ·  ", smallFont, secondary, //$NON-NLS-1$
                        x, event.y, event.height);
                paintPart(gc, choice.commitSubject(), originalFont, primary, x, event.y, event.height);
            }
            finally
            {
                gc.setFont(originalFont);
                gc.setForeground(originalColor);
            }
        });
        Display display = tree.getDisplay();
        search.addModifyListener(event -> scheduleSearchRebuild(display));
        Listener quickSearch = event ->
        {
            Control focus = display.getFocusControl();
            char character = event.character;
            if (focus != search && !(focus instanceof Button)
                && !(focus instanceof Combo) && isInside(focus, panel) && !Character.isISOControl(character)
                && character != 0 && (event.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND)) == 0)
            {
                search.setText(search.getText() + character);
                search.setFocus();
                search.setSelection(search.getText().length());
                event.doit = false;
            }
        };
        display.addFilter(SWT.KeyDown, quickSearch);
        panel.addListener(SWT.Dispose, event ->
        {
            display.removeFilter(SWT.KeyDown, quickSearch);
            if (pendingSearchRebuild != null)
                display.timerExec(-1, pendingSearchRebuild);
        });
        search.addListener(SWT.KeyDown, event ->
        {
            if (event.keyCode == SWT.ARROW_DOWN)
            {
                rebuildNow();
                TreeItem first = firstLeaf();
                if (first != null)
                {
                    tree.setSelection(first);
                    choose((Choice) first.getData());
                    tree.setFocus();
                }
            }
        });
        search.addListener(SWT.DefaultSelection, event ->
        {
            if (!search.getText().isBlank())
            {
                rebuildNow();
                TreeItem first = firstLeaf();
                if (first != null)
                {
                    choose((Choice) first.getData());
                    onActivate.run();
                }
            }
        });
        tree.addListener(SWT.Selection, event ->
        {
            selected = tree.getSelectionCount() == 1 && event.item instanceof TreeItem item
                && item.getData() instanceof Choice choice ? choice : null;
            favoriteButton.setEnabled(selected != null);
            updateFavoriteButton();
            onSelect.accept(selected);
        });
        tree.addListener(SWT.DefaultSelection, event ->
        {
            if (tree.getSelectionCount() > 1)
                return;
            if (event.item instanceof TreeItem item && item.getData() instanceof Choice choice)
            {
                choose(choice);
                onActivate.run();
            }
            else if (event.item instanceof TreeItem item)
                item.setExpanded(!item.getExpanded());
        });
        favoriteButton.addListener(SWT.Selection, event -> toggleFavorite());
        contextMenu = new Menu(tree);
        tree.setMenu(contextMenu);
        tree.addListener(SWT.MenuDetect, event ->
        {
            contextBranches = List.of();
            TreeItem[] selection = tree.getSelection();
            boolean keyboard = event.detail == SWT.MENU_KEYBOARD;
            TreeItem item = keyboard ? (selection.length == 0 ? null : selection[0])
                : tree.getItem(tree.toControl(event.x, event.y));
            if (selection.length > 1 && Arrays.asList(selection).contains(item))
            {
                List<Choice> selectedChoices = Arrays.stream(selection).map(TreeItem::getData)
                    .filter(Choice.class::isInstance).map(Choice.class::cast).toList();
                if (selectedChoices.stream().allMatch(choice -> !choice.remote() && choice.kind() == Kind.BRANCH))
                    contextBranches = selectedChoices.stream().map(Choice::name).distinct().toList();
                event.doit = !contextBranches.isEmpty();
                return;
            }
            if (item != null && item.getData() instanceof Folder folder && folder.remote())
            {
                tree.setData("contextFolder", folder); //$NON-NLS-1$
                tree.setData("contextChoice", null); //$NON-NLS-1$
            }
            else if (item != null && item.getData() instanceof Choice choice)
            {
                tree.setData("contextFolder", null); //$NON-NLS-1$
                tree.setData("contextChoice", choice); //$NON-NLS-1$
            }
            else
                event.doit = false;
        });
        contextMenu.addListener(SWT.Show, event ->
        {
            for (MenuItem item : contextMenu.getItems())
                item.dispose();
            if (!contextBranches.isEmpty())
            {
                List<String> names = contextBranches;
                contextBranches = List.of();
                menuItem(contextMenu, Messages.get("deleteLocalBranches"), () -> deleteSelectedBranches(names));
                return;
            }
            if (tree.getData("contextFolder") instanceof Folder folder) //$NON-NLS-1$
            {
                tree.setData("contextFolder", null); //$NON-NLS-1$
                MenuItem pin = new MenuItem(contextMenu, SWT.PUSH);
                pin.setText(Messages.get(pinnedFolders.contains(folder.path())
                    ? "unpinFolder" : "pinFolder")); //$NON-NLS-1$ //$NON-NLS-2$
                pin.addListener(SWT.Selection, click -> toggleFolder(folder.path()));
                return;
            }
            if (tree.getData("contextChoice") instanceof Choice choice) //$NON-NLS-1$
                showBranchMenu(choice);
        });
        rebuild();
        load(repository, includeRemote);
    }

    private void load(Repository repository, boolean includeRemote)
    {
        String mergeTarget = selectedMergeTarget;
        Job job = new Job(Messages.get("searchBranches")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                Set<String> localNames = new LinkedHashSet<>();
                List<Choice> local = new ArrayList<>();
                try (RevWalk walk = new RevWalk(repository))
                {
                    List<Ref> targets = filterLocalBranches
                        ? BranchOperations.mergeTargets(repository, mergeTarget) : List.of();
                    List<Ref> automaticTargets = mergeTarget == null ? targets
                        : BranchOperations.mergeTargets(repository, null);
                    for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS))
                    {
                        if (monitor.isCanceled())
                            return Status.CANCEL_STATUS;
                        String name = Repository.shortenRefName(ref.getName());
                        localNames.add(name);
                        boolean gone = filterLocalBranches && BranchOperations.isUpstreamGone(repository, name);
                        Ref merged = BranchOperations.isMergeTarget(name, automaticTargets) ? null
                            : BranchOperations.mergedTarget(walk, ref, targets);
                        String mergedInto = merged == null ? null : Repository.shortenRefName(merged.getName());
                        local.add(choice(walk, name, ref, false, true, Kind.BRANCH, gone, mergedInto));
                    }
                    local.sort(byActivity());
                }
                catch (IOException e)
                {
                    postError(e, true);
                    return Status.OK_STATUS;
                }
                post(local, () -> localLoading = false);
                if (includeRemote)
                {
                    List<Choice> remote = new ArrayList<>();
                    try (RevWalk walk = new RevWalk(repository))
                    {
                        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES))
                        {
                            if (monitor.isCanceled())
                                return Status.CANCEL_STATUS;
                            if (!ref.isSymbolic() && !ref.getName().endsWith("/HEAD")) //$NON-NLS-1$
                            {
                                String name = Repository.shortenRefName(ref.getName());
                                remote.add(choice(walk, name, ref, true,
                                    localNames.contains(name.substring(name.indexOf('/') + 1)), Kind.BRANCH));
                            }
                        }
                        remote.sort(byActivity());
                    }
                    catch (IOException e)
                    {
                        postError(e, false);
                        return Status.OK_STATUS;
                    }
                    post(remote, () -> remoteLoading = false);
                }
                if (!includeTags)
                    return Status.OK_STATUS;
                List<Choice> tags = new ArrayList<>();
                String error = null;
                try (RevWalk walk = new RevWalk(repository))
                {
                    for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS))
                    {
                        if (monitor.isCanceled())
                            return Status.CANCEL_STATUS;
                        tags.add(choice(walk, Repository.shortenRefName(ref.getName()),
                            repository.getRefDatabase().peel(ref), false, false, Kind.TAG));
                    }
                    tags.sort(byActivity());
                }
                catch (IOException e)
                {
                    error = Messages.get("tagListFailed") + " " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                String finalError = error;
                post(tags, () ->
                {
                    tagLoadError = finalError;
                    tagsLoading = false;
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        panel.addListener(SWT.Dispose, event -> job.cancel());
        job.schedule();
    }

    private void post(List<Choice> choices, Runnable update)
    {
        Display.getDefault().asyncExec(() ->
        {
            if (panel.isDisposed())
                return;
            branches.addAll(choices);
            choices.stream().filter(choice -> favorites.contains(choice.ref())).forEach(favoriteChoices::add);
            List<BranchPickerIndex.Ref> indexed = choices.stream().map(choice -> new BranchPickerIndex.Ref(
                choice.name(), choice.ref(), choice.kind() == Kind.TAG ? BranchPickerIndex.Group.TAG
                    : choice.remote() ? BranchPickerIndex.Group.REMOTE : BranchPickerIndex.Group.LOCAL,
                choice.lastActivity())).toList();
            choices.forEach(choice -> choicesByRef.put(choice.ref(), choice));
            branchIndex.addAll(indexed);
            update.run();
            rebuild();
        });
    }

    private void postError(IOException error, boolean local)
    {
        post(List.of(), () ->
        {
            if (local)
            {
                localLoadError = Messages.get("branchListFailed") + " " + error.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
                localLoading = false;
                remoteLoading = false;
            }
            else
            {
                remoteLoadError = Messages.get("branchListFailed") + " " + error.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
                remoteLoading = false;
            }
            tagsLoading = false;
        });
    }

    Composite control()
    {
        return panel;
    }

    void focusSearch()
    {
        if (!search.isDisposed())
            search.setFocus();
    }

    private void choose(Choice choice)
    {
        selected = choice;
        favoriteButton.setEnabled(true);
        updateFavoriteButton();
        onSelect.accept(choice);
    }

    private static boolean isInside(Control control, Composite parent)
    {
        while (control != null)
        {
            if (control == parent)
                return true;
            control = control.getParent();
        }
        return false;
    }

    private void toggleFavorite()
    {
        if (selected == null)
            return;
        boolean wasFavorite = favorites.contains(selected.ref());
        if (!favorites.add(selected.ref()))
            favorites.remove(selected.ref());
        preferences.put("refs", String.join("\n", favorites)); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            preferences.flush();
        }
        catch (BackingStoreException e)
        {
            if (wasFavorite)
                favorites.add(selected.ref());
            else
                favorites.remove(selected.ref());
            preferences.put("refs", String.join("\n", favorites)); //$NON-NLS-1$ //$NON-NLS-2$
            MessageDialog.openError(tree.getShell(), Messages.get("title"), //$NON-NLS-1$
                Messages.get("favoriteSaveFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        refreshFavoriteChoices();
        rebuild();
    }

    private void toggleFolder(String path)
    {
        boolean wasPinned = pinnedFolders.contains(path);
        if (!pinnedFolders.add(path))
            pinnedFolders.remove(path);
        preferences.put("folders", String.join("\n", pinnedFolders)); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            preferences.flush();
        }
        catch (BackingStoreException e)
        {
            if (wasPinned)
                pinnedFolders.add(path);
            else
                pinnedFolders.remove(path);
            preferences.put("folders", String.join("\n", pinnedFolders)); //$NON-NLS-1$ //$NON-NLS-2$
            MessageDialog.openError(tree.getShell(), Messages.get("title"), //$NON-NLS-1$
                Messages.get("folderSaveFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        branchIndex.setPinnedFolders(pinnedFolders);
        rebuild();
    }

    private void showBranchMenu(Choice choice)
    {
        menuItem(contextMenu, Messages.get("copyBranchName"), //$NON-NLS-1$
            () -> copyToClipboard(choice.name()));
        if (choice.kind() == Kind.TAG)
            return;
        new MenuItem(contextMenu, SWT.SEPARATOR);
        if (choice.remote())
        {
            menuItem(contextMenu, Messages.get("checkoutRemoteBranch"), () -> checkout(choice)); //$NON-NLS-1$
            menuItem(contextMenu, Messages.get("renameRemoteBranch"), () -> renameRemote(choice)); //$NON-NLS-1$
            return;
        }
        if (choice.name().equals(currentBranch))
        {
            menuItem(contextMenu, Messages.get("pullBranch"), () -> schedulePull()); //$NON-NLS-1$
            menuItem(contextMenu, Messages.get("pushBranch"), () -> schedulePush()); //$NON-NLS-1$
        }
        else
        {
            menuItem(contextMenu, Messages.get("checkoutBranch"), () -> checkout(choice)); //$NON-NLS-1$
            menuItem(contextMenu, Messages.get("renameBranch"), () -> rename(choice)); //$NON-NLS-1$
            menuItem(contextMenu, Messages.get("deleteBranch"), () -> delete(choice)); //$NON-NLS-1$
        }
    }

    private static MenuItem menuItem(Menu menu, String title, Runnable action)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(title);
        item.addListener(SWT.Selection, event -> action.run());
        return item;
    }

    private void copyToClipboard(String text)
    {
        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
    }

    private void checkout(Choice choice)
    {
        String localName = choice.localName();
        boolean create = choice.remote() && !choice.localExists();
        OperationJob.schedule(repository, tree.getShell(), Messages.get("checkoutTitle"), //$NON-NLS-1$
            (selectedRepository, monitor) -> BranchOperations.checkout(selectedRepository, localName, create,
                create ? choice.ref() : null, monitor), null,
            result ->
            {
                if (result.workspaceChanged())
                    ProjectRefreshSupport.afterCheckout(repository, result.affectedPaths());
                refreshAfter(result);
            });
    }

    private void schedulePull()
    {
        OperationJob.schedule(repository, tree.getShell(), Messages.get("pullBranch"), //$NON-NLS-1$
            PullOperations::smartPull, null, this::refreshAfter);
    }

    private void schedulePush()
    {
        OperationJob.schedule(repository, tree.getShell(), Messages.get("pushBranch"), //$NON-NLS-1$
            PullOperations::smartPush, null, this::refreshAfter);
    }

    private void rename(Choice choice)
    {
        InputDialog dialog = new InputDialog(tree.getShell(), Messages.get("renameBranch"), //$NON-NLS-1$
            Messages.get("renameBranchPrompt"), choice.name(), value -> //$NON-NLS-1$
                BranchOperations.isValidBranchName(value) ? null : Messages.get("invalidBranch")); //$NON-NLS-1$
        if (dialog.open() != Window.OK)
            return;
        String newName = dialog.getValue();
        BranchOperations.RemoteRename remoteTarget = BranchOperations.remoteRenameTarget(repository,
            choice.name(), newName);
        String confirmation = remoteTarget == null
            ? MessageFormat.format(Messages.get("renameBranchConfirm"), choice.name(), newName) //$NON-NLS-1$
            : MessageFormat.format(Messages.get("renameBranchConfirmRemote"), choice.name(), newName); //$NON-NLS-1$
        if (!MessageDialog.openConfirm(tree.getShell(), Messages.get("renameBranch"), confirmation)) //$NON-NLS-1$
            return;
        if (remoteTarget == null)
            OperationJob.schedule(repository, tree.getShell(), Messages.get("renameBranch"), //$NON-NLS-1$
                (selectedRepository, monitor) -> BranchOperations.rename(selectedRepository, choice.name(), newName,
                    monitor), null, this::refreshAfter);
        else
            OperationJob.schedule(repository, tree.getShell(), Messages.get("renameBranch"), //$NON-NLS-1$
                (selectedRepository, monitor) -> BranchOperations.renameWithRemote(selectedRepository,
                    choice.name(), newName, remoteTarget, monitor), null, this::refreshAfterRemoteRename);
    }

    private void renameRemote(Choice choice)
    {
        String remote = branchIndex.remoteName(choice.ref());
        if (remote == null)
            return;
        String prefix = Constants.R_REMOTES + remote + "/"; //$NON-NLS-1$
        String oldName = choice.ref().substring(prefix.length());
        InputDialog dialog = new InputDialog(tree.getShell(), Messages.get("renameRemoteBranch"), //$NON-NLS-1$
            Messages.get("renameRemoteBranchPrompt"), oldName, value -> //$NON-NLS-1$
                BranchOperations.isValidBranchName(value) ? null : Messages.get("invalidBranch")); //$NON-NLS-1$
        if (dialog.open() != Window.OK)
            return;
        String newName = dialog.getValue();
        String prompt = MessageFormat.format(Messages.get("renameRemoteBranchConfirm"), //$NON-NLS-1$
            remote + "/" + oldName, remote + "/" + newName); //$NON-NLS-1$
        if (!MessageDialog.openConfirm(tree.getShell(), Messages.get("renameRemoteBranch"), prompt)) //$NON-NLS-1$
            return;
        OperationJob.schedule(repository, tree.getShell(), Messages.get("renameRemoteBranch"), //$NON-NLS-1$
            (selectedRepository, monitor) -> BranchOperations.renameRemoteReference(selectedRepository,
                remote, oldName, newName, monitor), null, result ->
                {
                    if (result.succeeded())
                        migrateFavorite(choice.ref(), Constants.R_REMOTES + remote + "/" + newName); //$NON-NLS-1$
                    refreshAfterRemoteRename(result);
                });
    }

    private void migrateFavorite(String oldRef, String newRef)
    {
        if (!favorites.remove(oldRef))
            return;
        favorites.add(newRef);
        saveFavorites();
    }

    private void saveFavorites()
    {
        preferences.put("refs", String.join("\n", favorites)); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            preferences.flush();
        }
        catch (BackingStoreException e)
        {
            MessageDialog.openError(tree.getShell(), Messages.get("title"), //$NON-NLS-1$
                Messages.get("favoriteSaveFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void delete(Choice choice)
    {
        if (choice.mergedInto() != null)
        {
            String mergeTarget = selectedMergeTarget;
            OperationJob.schedule(repository, tree.getShell(), Messages.get("deleteBranch"), //$NON-NLS-1$
                (repo, monitor) -> BranchOperations.deleteInactiveBranches(repo, List.of(choice.name()),
                    mergeTarget, monitor), null, this::refreshAfter);
            return;
        }
        OperationJob.schedule(repository, tree.getShell(), Messages.get("deleteBranch"), //$NON-NLS-1$
            (selectedRepository, monitor) -> BranchOperations.delete(selectedRepository, choice.name(), false,
                monitor),
            (selectedRepository, monitor) -> BranchOperations.delete(selectedRepository, choice.name(), true,
                monitor), this::refreshAfter);
    }

    private void chooseMergeTarget()
    {
        List<String> targets = new ArrayList<>();
        String automatic = "";
        targets.add(automatic);
        branches.stream().filter(choice -> choice.kind() == Kind.BRANCH).map(Choice::ref)
            .distinct().sorted().forEach(targets::add);
        ListDialog dialog = new ListDialog(tree.getShell());
        dialog.setTitle(Messages.get("branchMergeTarget"));
        dialog.setContentProvider(ArrayContentProvider.getInstance());
        dialog.setLabelProvider(new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                String ref = (String) element;
                return ref.isEmpty() ? Messages.get("automaticMergeTargets")
                    : Repository.shortenRefName(ref);
            }
        });
        dialog.setInput(targets);
        dialog.setInitialSelections(new Object[] { selectedMergeTarget == null ? automatic : selectedMergeTarget });
        if (dialog.open() == Window.OK && dialog.getResult().length > 0)
        {
            String target = (String) dialog.getResult()[0];
            selectedMergeTarget = target.isEmpty() ? null : target;
            refreshBranches();
        }
    }

    private void deleteSelectedBranches(List<String> names)
    {
        var shell = tree.getShell();
        String title = Messages.get("deleteLocalBranches");
        String mergeTarget = selectedMergeTarget;
        OperationJob.schedule(repository, shell, title,
            (repo, monitor) -> BranchOperations.deleteInactiveBranches(repo, names, mergeTarget, monitor),
            null, result ->
            {
                if (!panel.isDisposed())
                    refreshBranches();
                if (!shell.isDisposed() && !result.succeeded())
                    MessageDialog.openWarning(shell, title, result.message());
            });
    }

    private boolean visible(Choice choice)
    {
        return switch (branchFilter)
        {
            case ALL -> true;
            case ACTIVE -> !closedOrMerged(choice) || choice.name().equals(currentBranch);
            case CLOSED_OR_MERGED -> closedOrMerged(choice);
        };
    }

    private static boolean closedOrMerged(Choice choice)
    {
        return choice.upstreamGone() || choice.mergedInto() != null;
    }

    private void refreshAfter(OperationResult result)
    {
        if (!result.succeeded() || panel.isDisposed())
            return;
        refreshBranches();
    }

    private void refreshAfterRemoteRename(OperationResult result)
    {
        if (!panel.isDisposed())
            refreshBranches();
    }

    private void refreshBranches()
    {
        branches.clear();
        favoriteChoices.clear();
        choicesByRef.clear();
        branchIndex.clear();
        localLoadError = null;
        remoteLoadError = null;
        tagLoadError = null;
        localLoading = true;
        remoteLoading = includeRemote;
        tagsLoading = includeTags;
        try
        {
            currentBranch = repository.getBranch();
        }
        catch (IOException e)
        {
            currentBranch = ""; //$NON-NLS-1$
        }
        rebuild();
        load(repository, includeRemote);
    }

    private void updateFavoriteButton()
    {
        favoriteButton.setText(Messages.get(selected != null && favorites.contains(selected.ref())
            ? "removeFavorite" : "addFavorite")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void rebuild()
    {
        if (pendingSearchRebuild != null)
        {
            tree.getDisplay().timerExec(-1, pendingSearchRebuild);
            pendingSearchRebuild = null;
        }
        tree.setRedraw(false);
        try
        {
            String selectedRef = selected == null ? null : selected.ref();
            tree.removeAll();
            updateSettingsMenu();
            boolean hierarchyEnabled = BranchHierarchySettings.isEnabled();
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            TreeItem starred = root(Messages.get("favorites")); //$NON-NLS-1$
            TreeItem local = root(Messages.get("localBranches")); //$NON-NLS-1$
            TreeItem remote = root(Messages.get("remoteBranches")); //$NON-NLS-1$
            TreeItem tags = includeTags ? root(Messages.get("tags")) : null; //$NON-NLS-1$
            boolean closedFilter = branchFilter == BranchFilter.CLOSED_OR_MERGED;
            var localMatches = branchIndex.filter(BranchPickerIndex.Group.LOCAL, query);
            List<Choice> localChoices = choices(localMatches).stream().filter(this::visible).toList();
            var remoteMatches = branchIndex.filter(BranchPickerIndex.Group.REMOTE, query);
            var tagMatches = branchIndex.filter(BranchPickerIndex.Group.TAG, query);
            boolean otherMatches = !remoteMatches.isEmpty() || !tagMatches.isEmpty();
            boolean searchAll = !query.isEmpty() && branchFilter != BranchFilter.ALL
                && localChoices.isEmpty() && (closedFilter || !otherMatches)
                && (!localMatches.isEmpty() || otherMatches);
            if (searchAll)
                localChoices = choices(localMatches);
            boolean closedOnly = closedFilter && !searchAll;
            for (Choice branch : closedOnly ? List.<Choice>of()
                : sorted(favoriteChoices).stream()
                    .filter(branch -> (searchAll || visible(branch))
                        && branch.name().toLowerCase(Locale.ROOT).contains(query)).toList())
            {
                TreeItem item = new TreeItem(starred, SWT.NONE);
                item.setText(rowText("★ " + branch.name(), branch)); //$NON-NLS-1$
                setBranchImage(item, branch);
                item.setData(branch);
                item.setData("label", "★ " + branch.name()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (hierarchyEnabled)
                addGroupedBranches(local, localChoices, BranchPickerIndex.Group.LOCAL);
            else
                addFlatBranches(local, localChoices);
            if (!closedOnly)
            {
                if (hierarchyEnabled)
                    addRemoteBranches(remote, query);
                else
                    addFlatBranches(remote, choices(remoteMatches));
                if (includeTags)
                    addFlatBranches(tags, choices(tagMatches));
            }
            if (localLoadError != null)
            {
                TreeItem status = new TreeItem(local, SWT.NONE);
                status.setText(localLoadError);
            }
            if (remoteLoadError != null)
            {
                TreeItem status = new TreeItem(remote, SWT.NONE);
                status.setText(remoteLoadError);
            }
            if (tags != null && (tagsLoading || tagLoadError != null))
            {
                TreeItem status = new TreeItem(tags, SWT.NONE);
                status.setText(tagLoadError == null ? Messages.get("loadingTags") : tagLoadError); //$NON-NLS-1$
            }
            starred.setExpanded(true);
            local.setExpanded(true);
            remote.setExpanded(true);
            if (closedOnly)
            {
                starred.dispose();
                remote.dispose();
                if (tags != null)
                    tags.dispose();
                if (local.getItemCount() == 0 && !localLoading)
                    new TreeItem(local, SWT.NONE).setText(Messages.get("goneBranchesEmpty"));
            }
            else if (starred.getItemCount() == 0 && !query.isEmpty())
                starred.dispose();
            else if (starred.getItemCount() == 0)
            {
                TreeItem empty = new TreeItem(starred, SWT.NONE);
                empty.setText(Messages.get("favoritesEmpty")); //$NON-NLS-1$
            }
            if (local.getItemCount() == 0 && !localLoading)
                local.dispose();
            if (!remote.isDisposed() && remote.getItemCount() == 0 && !remoteLoading)
                remote.dispose();
            if (tags != null && !tags.isDisposed() && tags.getItemCount() == 0)
                tags.dispose();
            if (!query.isEmpty())
            {
                if (!local.isDisposed())
                    expandGroups(local);
                if (!remote.isDisposed())
                    expandGroups(remote);
                if (tags != null && !tags.isDisposed())
                    expandGroups(tags);
            }
            if (selectedRef == null || !select(selectedRef))
            {
                selected = null;
                favoriteButton.setEnabled(false);
                updateFavoriteButton();
                onSelect.accept(null);
            }
        }
        finally
        {
            tree.setRedraw(true);
        }
    }

    private List<Choice> choices(List<BranchPickerIndex.Ref> refs)
    {
        return sorted(refs.stream().map(ref -> choicesByRef.get(ref.ref()))
            .filter(choice -> choice != null).toList());
    }

    private List<Choice> sorted(List<Choice> choices)
    {
        Comparator<Choice> byName = Comparator
            .comparing((Choice choice) -> choice.name().toLowerCase(Locale.ROOT)).thenComparing(Choice::name);
        Comparator<Choice> comparator = switch (sortOrder)
        {
            case ACTIVITY_NEWEST_FIRST -> byActivity();
            case ACTIVITY_OLDEST_FIRST -> Comparator.comparingInt(Choice::lastActivity)
                .thenComparing(Choice::name);
            case NAME_ASCENDING -> byName;
            case NAME_DESCENDING -> byName.reversed();
        };
        return choices.stream().sorted(comparator).toList();
    }

    private void addFlatBranches(TreeItem root, List<Choice> choices)
    {
        for (Choice choice : choices)
        {
            String label = favorites.contains(choice.ref()) ? "★ " + choice.name() : choice.name(); //$NON-NLS-1$
            TreeItem item = new TreeItem(root, SWT.NONE);
            item.setText(rowText(label, choice));
            setBranchImage(item, choice);
            item.setData(choice);
            item.setData("label", label); //$NON-NLS-1$
        }
    }

    private MenuItem radioMenuItem(String key)
    {
        MenuItem item = new MenuItem(settingsMenu, SWT.RADIO);
        item.setText(Messages.get(key));
        return item;
    }

    private void chooseSortOrder(MenuItem item, SortOrder order)
    {
        if (item.getSelection())
        {
            sortOrder = order;
            rebuild();
        }
    }

    private void updateSettingsMenu()
    {
        activityNewestItem.setSelection(sortOrder == SortOrder.ACTIVITY_NEWEST_FIRST);
        activityOldestItem.setSelection(sortOrder == SortOrder.ACTIVITY_OLDEST_FIRST);
        nameAscendingItem.setSelection(sortOrder == SortOrder.NAME_ASCENDING);
        nameDescendingItem.setSelection(sortOrder == SortOrder.NAME_DESCENDING);
        State state = BranchHierarchySettings.state();
        hierarchyItem.setSelection(state != null && Boolean.TRUE.equals(state.getValue()));
        hierarchyItem.setEnabled(state != null);
    }

    private void setHierarchyEnabled(boolean enabled)
    {
        State state = BranchHierarchySettings.state();
        if (state == null)
            return;
        state.setValue(enabled);
        updateSettingsMenu();
        rebuild();
    }

    private void refreshFavoriteChoices()
    {
        favoriteChoices.clear();
        for (Choice branch : branches)
            if (favorites.contains(branch.ref()))
                favoriteChoices.add(branch);
    }

    private void scheduleSearchRebuild(Display display)
    {
        if (pendingSearchRebuild != null)
            display.timerExec(-1, pendingSearchRebuild);
        Runnable rebuildSearch = () ->
        {
            pendingSearchRebuild = null;
            if (!panel.isDisposed())
                rebuild();
        };
        pendingSearchRebuild = rebuildSearch;
        display.timerExec(100, rebuildSearch);
    }

    private void rebuildNow()
    {
        if (pendingSearchRebuild != null)
        {
            tree.getDisplay().timerExec(-1, pendingSearchRebuild);
            pendingSearchRebuild = null;
            rebuild();
        }
    }

    private static void expandGroups(TreeItem item)
    {
        for (TreeItem child : item.getItems())
            expandGroups(child);
        if (item.getItemCount() > 0)
            item.setExpanded(true);
    }

    private TreeItem root(String label)
    {
        TreeItem item = new TreeItem(tree, SWT.NONE);
        item.setText(label);
        item.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_FOLDER));
        return item;
    }

    private void addGrouped(TreeItem root, Choice branch, List<String> parts, String prefix,
        Map<String, TreeItem> folders)
    {
        TreeItem parent = root;
        String path = prefix;
        for (int i = 0; i < parts.size(); i++)
        {
            String part = parts.get(i);
            path = path.isEmpty() ? part : path + "/" + part; //$NON-NLS-1$
            TreeItem item = i < parts.size() - 1 ? folders.get(path) : null;
            if (item == null)
            {
                item = new TreeItem(parent, SWT.NONE);
                if (i < parts.size() - 1)
                {
                    item.setText(pinnedFolders.contains(path) ? "★ " + part : part); //$NON-NLS-1$
                    item.setImage(PlatformUI.getWorkbench().getSharedImages()
                        .getImage(ISharedImages.IMG_OBJ_FOLDER));
                    item.setData(new Folder(path, branch.remote()));
                    folders.put(path, item);
                }
                else
                {
                    String label = favorites.contains(branch.ref()) ? "★ " + part : part; //$NON-NLS-1$
                    item.setText(rowText(label, branch));
                    setBranchImage(item, branch);
                    item.setData("label", label); //$NON-NLS-1$
                }
            }
            if (i == parts.size() - 1)
            {
                item.setData(branch);
            }
            else
            {
                item.setExpanded(true);
            }
            parent = item;
        }
    }

    private void addGroupedBranches(TreeItem root, List<Choice> choices, BranchPickerIndex.Group group)
    {
        Map<String, TreeItem> folderItems = new HashMap<>();
        Set<String> visiblePaths = new LinkedHashSet<>();
        for (Choice choice : choices)
        {
            String path = choice.name();
            for (int slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/'))
            {
                path = path.substring(0, slash);
                visiblePaths.add(path);
            }
        }
        for (String path : branchIndex.folderPaths(group, search.getText(), null))
            if (visiblePaths.contains(path))
                addFolderPath(root, branchIndex.folderParts(path), "", false, folderItems); //$NON-NLS-1$
        for (Choice choice : choices)
            addGrouped(root, choice, branchIndex.pathParts(choice.ref()), "", folderItems); //$NON-NLS-1$
    }

    private void addRemoteBranches(TreeItem root, String query)
    {
        for (String remoteName : branchIndex.remoteNames(query))
        {
            List<Choice> remoteChoices = choices(branchIndex.filterRemote(query, remoteName));
            Map<String, TreeItem> folderItems = new HashMap<>();
            TreeItem remoteRoot = new TreeItem(root, SWT.NONE);
            remoteRoot.setText(pinnedFolders.contains(remoteName) ? "★ " + remoteName : remoteName); //$NON-NLS-1$
            remoteRoot.setImage(PlatformUI.getWorkbench().getSharedImages()
                .getImage(ISharedImages.IMG_OBJ_FOLDER));
            remoteRoot.setData(new Folder(remoteName, true));
            folderItems.put(remoteName, remoteRoot);
            for (String path : branchIndex.folderPaths(BranchPickerIndex.Group.REMOTE, query, remoteName))
            {
                List<String> parts = branchIndex.folderParts(path);
                addFolderPath(remoteRoot, parts.subList(1, parts.size()), remoteName, true, folderItems);
            }
            for (Choice choice : remoteChoices)
                addGrouped(remoteRoot, choice, branchIndex.pathParts(choice.ref()), remoteName, folderItems);
            remoteRoot.setExpanded(true);
        }
    }

    private void addFolderPath(TreeItem root, List<String> parts, String prefix, boolean remote,
        Map<String, TreeItem> folders)
    {
        String path = prefix;
        TreeItem parent = root;
        for (String part : parts)
        {
            path = path.isEmpty() ? part : path + "/" + part; //$NON-NLS-1$
            TreeItem folder = folders.get(path);
            if (folder == null)
            {
                folder = new TreeItem(parent, SWT.NONE);
                folder.setText(pinnedFolders.contains(path) ? "★ " + part : part); //$NON-NLS-1$
                folder.setImage(PlatformUI.getWorkbench().getSharedImages()
                    .getImage(ISharedImages.IMG_OBJ_FOLDER));
                folder.setData(new Folder(path, remote));
                folders.put(path, folder);
            }
            folder.setExpanded(true);
            parent = folder;
        }
    }

    private static Comparator<Choice> byActivity()
    {
        return Comparator.comparingInt(Choice::lastActivity).reversed().thenComparing(Choice::name);
    }

    private static Choice choice(RevWalk walk, String name, Ref ref, boolean remote, boolean localExists, Kind kind)
        throws IOException
    {
        return choice(walk, name, ref, remote, localExists, kind, false, null);
    }

    private static Choice choice(RevWalk walk, String name, Ref ref, boolean remote, boolean localExists, Kind kind,
        boolean upstreamGone, String mergedInto) throws IOException
    {
        if (ref.getObjectId() == null)
            return new Choice(name, ref.getName(), remote, localExists, 0, "", "", kind, upstreamGone, mergedInto); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            var objectId = ref.getPeeledObjectId() == null ? ref.getObjectId() : ref.getPeeledObjectId();
            var commit = walk.parseCommit(objectId);
            return new Choice(name, ref.getName(), remote, localExists, commit.getCommitTime(),
                commit.getAuthorIdent().getName(), commit.getShortMessage(), kind, upstreamGone, mergedInto);
        }
        catch (IOException e)
        {
            if (kind == Kind.BRANCH)
                throw e;
            return new Choice(name, ref.getName(), remote, localExists, 0, "", "", kind, upstreamGone, mergedInto); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static ImageDescriptor egitImage(String path)
    {
        var bundle = Platform.getBundle("org.eclipse.egit.ui"); //$NON-NLS-1$
        URL entry = bundle == null ? null : bundle.getEntry(path);
        if (entry != null)
            return ImageDescriptor.createFromURL(entry);
        return PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJ_ELEMENT);
    }

    private void setBranchImage(TreeItem item, Choice branch)
    {
        Image image = branch.kind() == Kind.TAG ? tagImage
            : !branch.remote() && branch.name().equals(currentBranch) ? currentBranchImage : branchImage;
        item.setImage(0, image);
    }

    private String branchStatus(Choice branch)
    {
        String gone = branch.upstreamGone() ? Messages.get("upstreamGone") : "";
        String merged = branch.mergedInto() != null ? Messages.get("branchMerged") : "";
        return gone.isEmpty() || merged.isEmpty() ? gone + merged : gone + "  ·  " + merged;
    }

    private String rowText(String name, Choice branch)
    {
        String age = branch.lastActivity() > 0
            ? "  " + relativeTime(branch.lastActivity()) : ""; //$NON-NLS-1$ //$NON-NLS-2$
        String author = branch.author().isEmpty() ? "" : "  ·  " + branch.author(); //$NON-NLS-1$ //$NON-NLS-2$
        String subject = branch.commitSubject().isEmpty() ? "" : "  ·  " + branch.commitSubject(); //$NON-NLS-1$ //$NON-NLS-2$
        String status = branchStatus(branch);
        return name + (status.isEmpty() ? "" : "  " + status) + age + author + subject;
    }

    private static int paintPart(GC gc, String text, Font font, Color color,
        int x, int y, int height)
    {
        gc.setFont(font);
        gc.setForeground(color);
        gc.drawText(text, x, y + Math.max(0, (height - gc.getFontMetrics().getHeight()) / 2), true);
        return x + gc.textExtent(text).x;
    }

    private static String relativeTime(int commitTime)
    {
        long seconds = Math.max(0, System.currentTimeMillis() / 1000 - commitTime);
        if (seconds < 60)
            return Messages.get("timeNow"); //$NON-NLS-1$
        String unit;
        long count;
        if (seconds < 3600)
        {
            unit = "timeMinute"; //$NON-NLS-1$
            count = seconds / 60;
        }
        else if (seconds < 86400)
        {
            unit = "timeHour"; //$NON-NLS-1$
            count = seconds / 3600;
        }
        else if (seconds < 604800)
        {
            unit = "timeDay"; //$NON-NLS-1$
            count = seconds / 86400;
        }
        else if (seconds < 2592000)
        {
            unit = "timeWeek"; //$NON-NLS-1$
            count = seconds / 604800;
        }
        else if (seconds < 31536000)
        {
            unit = "timeMonth"; //$NON-NLS-1$
            count = seconds / 2592000;
        }
        else
        {
            unit = "timeYear"; //$NON-NLS-1$
            count = seconds / 31536000;
        }
        String[] forms = Messages.get(unit).split("\\|"); //$NON-NLS-1$
        int form = forms.length == 2 ? (count == 1 ? 0 : 1)
            : count % 100 >= 11 && count % 100 <= 14 ? 2
            : count % 10 == 1 ? 0 : count % 10 >= 2 && count % 10 <= 4 ? 1 : 2;
        return MessageFormat.format(Messages.get("timeAgo"), count, forms[form]); //$NON-NLS-1$
    }

    private TreeItem firstLeaf()
    {
        for (TreeItem root : tree.getItems())
        {
            if (!search.getText().isBlank()
                && root.getText().equals(Messages.get("favorites"))) //$NON-NLS-1$
                continue;
            TreeItem found = firstLeaf(root);
            if (found != null)
                return found;
        }
        return null;
    }

    private static TreeItem firstLeaf(TreeItem item)
    {
        if (item.getData() instanceof Choice)
            return item;
        for (TreeItem child : item.getItems())
        {
            TreeItem found = firstLeaf(child);
            if (found != null)
                return found;
        }
        return null;
    }

    private boolean select(String ref)
    {
        for (TreeItem root : tree.getItems())
        {
            TreeItem found = find(root, ref);
            if (found != null)
            {
                tree.setSelection(found);
                choose((Choice) found.getData());
                return true;
            }
        }
        return false;
    }

    private static TreeItem find(TreeItem item, String ref)
    {
        if (item.getData() instanceof Choice choice && choice.ref().equals(ref))
            return item;
        for (TreeItem child : item.getItems())
        {
            TreeItem found = find(child, ref);
            if (found != null)
                return found;
        }
        return null;
    }
}
