package xyz.selykvpn.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.selykvpn.app.R
import xyz.selykvpn.app.data.RouteAction
import xyz.selykvpn.app.data.RouteRules
import xyz.selykvpn.app.data.TransferKind
import xyz.selykvpn.app.ui.AppViewModel
import xyz.selykvpn.app.ui.common.centeredMaxWidth

/** Lists longer than this are folded; the full list and its search open on demand. */
private const val FOLDED_COUNT = 5
private const val SEARCH_FROM = 10

private val TOP = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val BOTTOM = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

/**
 * Simple routing: two ready-made switches and three lists (directly, through VPN, blocked) of
 * sites or IP addresses. The rules are applied on top of the server's routing. Long lists are
 * folded and drawn lazily, so even thousands of addresses stay smooth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val routing by viewModel.routing.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf<RouteAction?>(null) }
    var clearing by rememberSaveable { mutableStateOf<RouteAction?>(null) }
    var editing by rememberSaveable { mutableStateOf<Pair<RouteAction, String>?>(null) }
    var expandedDirect by rememberSaveable { mutableStateOf(false) }
    var expandedProxy by rememberSaveable { mutableStateOf(false) }
    var expandedBlock by rememberSaveable { mutableStateOf(false) }
    var queryDirect by rememberSaveable { mutableStateOf("") }
    var queryProxy by rememberSaveable { mutableStateOf("") }
    var queryBlock by rememberSaveable { mutableStateOf("") }
    val sortedDirect = remember(routing.direct) { routing.direct.sortedBy { RouteRules.display(it) } }
    val sortedProxy = remember(routing.proxy) { routing.proxy.sortedBy { RouteRules.display(it) } }
    val sortedBlock = remember(routing.block) { routing.block.sortedBy { RouteRules.display(it) } }
    val dim = Modifier.alpha(if (routing.enabled) 1f else 0.5f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                actions = { TransferMenu(TransferKind.ROUTING, viewModel) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .centeredMaxWidth(720.dp)
                .padding(horizontal = 16.dp),
        ) {
            item(key = "enabled") {
                Card(
                    onClick = { viewModel.setRoutingEnabled(!routing.enabled) },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.routing_enabled), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.routing_enabled_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = routing.enabled, onCheckedChange = viewModel::setRoutingEnabled)
                    }
                }
            }
            item(key = "presets") {
                // Dimmed while the user's rules are off.
                Column(dim) {
                    Text(
                        stringResource(R.string.routing_presets),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 12.dp),
                    )
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        PresetRow(
                            icon = Icons.Rounded.Lan,
                            title = R.string.routing_lan,
                            hint = R.string.routing_lan_hint,
                            checked = routing.lanDirect,
                            enabled = routing.enabled,
                            onChange = viewModel::setRoutingLanDirect,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PresetRow(
                            icon = Icons.Rounded.Language,
                            title = R.string.routing_ru,
                            hint = R.string.routing_ru_hint,
                            checked = routing.ruDirect,
                            enabled = routing.enabled,
                            onChange = viewModel::setRoutingRuDirect,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            ruleGroup(
                action = RouteAction.DIRECT,
                icon = Icons.Rounded.Public,
                hint = R.string.routing_direct_hint,
                entries = sortedDirect,
                expanded = expandedDirect,
                query = queryDirect,
                enabled = routing.enabled,
                dim = dim,
                onExpand = { expandedDirect = it },
                onQuery = { queryDirect = it },
                onAdd = { adding = RouteAction.DIRECT },
                onClear = { clearing = RouteAction.DIRECT },
                onRemove = { viewModel.removeRoutingRule(RouteAction.DIRECT, it) },
                onEdit = { editing = RouteAction.DIRECT to it },
            )
            ruleGroup(
                action = RouteAction.PROXY,
                icon = Icons.Rounded.Shield,
                hint = R.string.routing_proxy_hint,
                entries = sortedProxy,
                expanded = expandedProxy,
                query = queryProxy,
                enabled = routing.enabled,
                dim = dim,
                onExpand = { expandedProxy = it },
                onQuery = { queryProxy = it },
                onAdd = { adding = RouteAction.PROXY },
                onClear = { clearing = RouteAction.PROXY },
                onRemove = { viewModel.removeRoutingRule(RouteAction.PROXY, it) },
                onEdit = { editing = RouteAction.PROXY to it },
            )
            ruleGroup(
                action = RouteAction.BLOCK,
                icon = Icons.Rounded.Block,
                hint = R.string.routing_block_hint,
                entries = sortedBlock,
                expanded = expandedBlock,
                query = queryBlock,
                enabled = routing.enabled,
                dim = dim,
                onExpand = { expandedBlock = it },
                onQuery = { queryBlock = it },
                onAdd = { adding = RouteAction.BLOCK },
                onClear = { clearing = RouteAction.BLOCK },
                onRemove = { viewModel.removeRoutingRule(RouteAction.BLOCK, it) },
                onEdit = { editing = RouteAction.BLOCK to it },
            )
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    adding?.let { action ->
        AddRulesDialog(
            groupTitle = stringResource(titleOf(action)),
            onDismiss = { adding = null },
            onAdd = { entries ->
                viewModel.addRoutingRules(action, entries)
                adding = null
            },
        )
    }

    editing?.let { (action, entry) ->
        EditRuleDialog(
            groupTitle = stringResource(titleOf(action)),
            entry = entry,
            onDismiss = { editing = null },
            onSave = { entries ->
                viewModel.replaceRoutingRule(action, entry, entries)
                editing = null
            },
        )
    }

    clearing?.let { action ->
        AlertDialog(
            onDismissRequest = { clearing = null },
            title = { Text(stringResource(R.string.routing_clear_title, stringResource(titleOf(action)))) },
            text = { Text(stringResource(R.string.routing_clear_text, routing.rules(action).size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearRoutingRules(action)
                    clearing = null
                }) { Text(stringResource(R.string.routing_clear)) }
            },
            dismissButton = { TextButton(onClick = { clearing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@StringRes
private fun titleOf(action: RouteAction) = when (action) {
    RouteAction.DIRECT -> R.string.routing_direct
    RouteAction.PROXY -> R.string.routing_proxy
    RouteAction.BLOCK -> R.string.routing_block
}

@Composable
private fun PresetRow(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes hint: Int,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

/**
 * One list as a card made of lazy rows: the header (pinned while an unfolded list scrolls, with the
 * search and the fold / remove-all buttons), then the first few addresses or all of them.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.ruleGroup(
    action: RouteAction,
    icon: ImageVector,
    @StringRes hint: Int,
    entries: List<String>,
    expanded: Boolean,
    query: String,
    enabled: Boolean,
    dim: Modifier,
    onExpand: (Boolean) -> Unit,
    onQuery: (String) -> Unit,
    onAdd: () -> Unit,
    onClear: () -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val foldable = entries.size > FOLDED_COUNT
    val open = expanded && foldable
    val visible = when {
        !open -> entries.take(FOLDED_COUNT)
        query.isBlank() -> entries
        else -> entries.filter { RouteRules.display(it).contains(query.trim(), ignoreCase = true) }
    }
    val header: @Composable () -> Unit = {
        Column(dim.segment(if (entries.isEmpty()) RoundedCornerShape(24.dp) else TOP)) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("${stringResource(titleOf(action))} · ${entries.size}", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onAdd, enabled = enabled) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.routing_add))
                }
            }
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.routing_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, bottom = 16.dp, top = 4.dp),
                )
            }
            if (open) {
                if (entries.size > SEARCH_FROM) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, null) }
                        },
                        placeholder = { Text(stringResource(R.string.routing_search)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 56.dp, end = 16.dp, top = 4.dp),
                    )
                }
                Row(Modifier.padding(start = 44.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FoldButton(open = true) { onExpand(false) }
                    Spacer(Modifier.weight(1f))
                    // Thousands of imported addresses must not have to be removed one by one.
                    TextButton(onClick = onClear, enabled = enabled) {
                        Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.routing_clear))
                    }
                }
            }
        }
    }
    // Every group header is pinned, so the next one pushes the previous one out while scrolling.
    stickyHeader(key = "head-$action") { header() }
    if (entries.isEmpty()) {
        item(key = "gap-$action") { Spacer(Modifier.height(12.dp)) }
        return
    }
    items(visible, key = { "$action/$it" }) { entry ->
        val haptic = LocalHapticFeedback.current
        Row(
            modifier = dim
                .segment(RectangleShape)
                // A tap or a long press opens the entry for editing (to fix a typo without retyping it).
                .combinedClickable(
                    enabled = enabled,
                    onClick = { onEdit(entry) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEdit(entry)
                    },
                )
                .heightIn(min = 44.dp)
                .padding(start = 56.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    RouteRules.display(entry),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                KindLabel(entry)
            }
            IconButton(onClick = { onRemove(entry) }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.routing_remove), Modifier.size(18.dp))
            }
        }
    }
    if (open && visible.isEmpty()) {
        item(key = "nothing-$action") {
            Text(
                stringResource(R.string.routing_nothing_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dim.segment(RectangleShape).padding(start = 56.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }
    item(key = "foot-$action") {
        if (foldable && !open) {
            Row(dim.segment(BOTTOM).padding(start = 44.dp, end = 4.dp, bottom = 4.dp)) {
                FoldButton(open = false, count = entries.size) { onExpand(true) }
            }
        } else {
            Spacer(dim.segment(BOTTOM).height(8.dp))
        }
    }
    item(key = "gap-$action") { Spacer(Modifier.height(12.dp)) }
}

@Composable
private fun FoldButton(open: Boolean, count: Int = 0, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(if (open) stringResource(R.string.routing_fold) else stringResource(R.string.routing_show_all, count))
        Spacer(Modifier.width(4.dp))
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
    }
}

/** A piece of a card that is split into several lazy rows. */
@Composable
private fun Modifier.segment(shape: Shape): Modifier =
    fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, shape)

@StringRes
private fun kindLabel(kind: RouteRules.Kind) = when (kind) {
    RouteRules.Kind.KEYWORD -> R.string.routing_kind_keyword
    RouteRules.Kind.FULL -> R.string.routing_kind_full
    RouteRules.Kind.REGEXP -> R.string.routing_kind_regexp
    RouteRules.Kind.DOMAIN, RouteRules.Kind.IP -> 0
}

/** "слово" for keyword:, "точно" for full:, "regexp" for regexp: entries; nothing for sites and IPs. */
@Composable
private fun KindLabel(entry: String) {
    val text = kindLabel(RouteRules.kindOf(entry)).takeIf { it != 0 } ?: return
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun EditRuleDialog(groupTitle: String, entry: String, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by rememberSaveable(entry) { mutableStateOf(RouteRules.editable(entry)) }
    var invalid by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_edit_title, groupTitle)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    invalid = emptyList()
                },
                singleLine = true,
                isError = invalid.isNotEmpty(),
                supportingText = {
                    Text(
                        if (invalid.isNotEmpty()) stringResource(R.string.routing_invalid, invalid.joinToString(", "))
                        else stringResource(R.string.routing_add_hint),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val (valid, bad) = RouteRules.parseList(text)
                    if (bad.isNotEmpty()) invalid = bad else if (valid.isNotEmpty()) onSave(valid)
                },
            ) { Text(stringResource(R.string.routing_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AddRulesDialog(groupTitle: String, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var invalid by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_add_title, groupTitle)) },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        invalid = emptyList()
                    },
                    label = { Text(stringResource(R.string.routing_add_label)) },
                    minLines = 3,
                    isError = invalid.isNotEmpty(),
                    supportingText = {
                        Text(
                            if (invalid.isNotEmpty()) stringResource(R.string.routing_invalid, invalid.joinToString(", "))
                            else stringResource(R.string.routing_add_hint),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    RouteRules.PREFIXES.forEach { (kind, prefix) ->
                        AssistChip(
                            onClick = {
                                val (text, cursor) = RouteRules.insertPrefix(field.text, field.selection.min, field.selection.max, prefix)
                                field = TextFieldValue(text, TextRange(cursor))
                                invalid = emptyList()
                                runCatching { focus.requestFocus() }
                            },
                            label = { Text(stringResource(kindLabel(kind))) },
                            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = field.text.isNotBlank(),
                onClick = {
                    val (valid, bad) = RouteRules.parseList(field.text)
                    if (bad.isNotEmpty()) invalid = bad else if (valid.isNotEmpty()) onAdd(valid)
                },
            ) { Text(stringResource(R.string.routing_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
