package io.github.pumpkinredbean.appagent.inspector

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pumpkinredbean.appagent.ToolApproval
import io.github.pumpkinredbean.appagent.ToolJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.launch

private val Background = Color(0xFFF6F7F9)
private val SurfaceColor = Color.White
private val Ink = Color(0xFF171A1F)
private val Muted = Color(0xFF626A76)
private val Line = Color(0xFFE1E4E9)
private val Accent = Color(0xFF3659C9)
private val Success = Color(0xFF147A55)
private val Warning = Color(0xFF9A6400)
private val Danger = Color(0xFFB73A3A)
private val Code = Color(0xFF171A21)

internal enum class HistoryTab { CALLS, APPROVALS }
private enum class CallFilter { ALL, APPROVAL, FAILED }
private enum class DecisionFilter { ALL, APPROVED, DENIED, TIMED_OUT }

@Composable
internal fun InspectorHistoryScreen(
    snapshot: InspectorSnapshot,
    onCallSelected: (String) -> Unit,
    initialTab: HistoryTab = HistoryTab.CALLS,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag(if (tab == HistoryTab.CALLS) "inspector-calls" else "inspector-approvals"),
        color = Background,
    ) {
        Column {
            InspectorHeader(snapshot, tab, onTabSelected = { tab = it })
            when (tab) {
                HistoryTab.CALLS -> CallHistory(snapshot.calls, onCallSelected)
                HistoryTab.APPROVALS -> ApprovalHistory(snapshot.calls, onCallSelected)
            }
        }
    }
}

@Composable
internal fun InspectorCallDetailScreen(
    record: InspectorCallRecord,
    onBack: () -> Unit,
) {
    var requestOpen by rememberSaveable(record.call.id) { mutableStateOf(true) }
    var approvalOpen by rememberSaveable(record.call.id) { mutableStateOf(true) }
    var validationOpen by rememberSaveable(record.call.id) { mutableStateOf(true) }
    var responseOpen by rememberSaveable(record.call.id) { mutableStateOf(true) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("inspector-call-detail"),
        color = Background,
    ) {
        Column {
            DetailHeader(record.call.id, onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailSummary(record)
                MetadataCard(record)
                LifecycleStrip(record.lifecycle)
                ExpandableSection(
                    title = "Request",
                    subtitle = "Tool call envelope and input",
                    expanded = requestOpen,
                    onToggle = { requestOpen = !requestOpen },
                    testTag = "request-section-toggle",
                ) {
                    JsonPayload(record.requestPayload.prettyJson(), "request-payload")
                }
                if (record.approvalPolicy == ToolApproval.MOBILE) {
                    ExpandableSection(
                        title = "Approval",
                        subtitle = "Runtime policy and resolution",
                        expanded = approvalOpen,
                        onToggle = { approvalOpen = !approvalOpen },
                        testTag = "approval-section-toggle",
                    ) {
                        ApprovalDetail(record)
                    }
                }
                if (record.validationViolations.isNotEmpty()) {
                    ExpandableSection(
                        title = "Validation",
                        subtitle = "Input violations reported by the runtime",
                        expanded = validationOpen,
                        onToggle = { validationOpen = !validationOpen },
                        testTag = "validation-section-toggle",
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            record.validationViolations.forEach {
                                Text(
                                    text = it.toString(),
                                    color = Danger,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
                ExpandableSection(
                    title = "Response",
                    subtitle = "Result returned to the agent",
                    expanded = responseOpen,
                    onToggle = { responseOpen = !responseOpen },
                    testTag = "response-section-toggle",
                ) {
                    val response = record.responsePayload
                    if (response == null) {
                        Text(
                            text = if (record.cancelledStage == null) {
                                "No response has been returned yet."
                            } else {
                                "The call was cancelled before a response was returned."
                            },
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    } else {
                        JsonPayload(response.prettyJson(), "response-payload")
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetachedInspectorScreen(onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Inspector unavailable", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Install AppAgentInspector before opening this screen.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun CallHistory(calls: List<InspectorCallRecord>, onSelected: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CallFilter.ALL) }
    val visible = remember(calls, query, filter) {
        calls.filter { record ->
            val queryMatches = query.isBlank() ||
                record.call.name.contains(query, true) ||
                record.call.id.contains(query, true) ||
                record.callState.label.contains(query, true)
            val filterMatches = when (filter) {
                CallFilter.ALL -> true
                CallFilter.APPROVAL -> record.approvalPolicy == ToolApproval.MOBILE
                CallFilter.FAILED -> record.callState in setOf(
                    InspectorCallState.FAILED,
                    InspectorCallState.DENIED,
                    InspectorCallState.CANCELLED,
                )
            }
            queryMatches && filterMatches
        }
    }
    CallSummary(calls)
    SearchBox(query, onChange = { query = it })
    FilterRow(
        entries = listOf(
            CallFilter.ALL to "All",
            CallFilter.APPROVAL to "Requires approval",
            CallFilter.FAILED to "Failed",
        ),
        selected = filter,
        onSelected = { filter = it },
    )
    HistoryList(
        records = visible,
        title = "CALL HISTORY",
        emptyMessage = if (calls.isEmpty()) "No tool calls in this session." else "No calls match this filter.",
        approvalMode = false,
        onSelected = onSelected,
    )
}

@Composable
private fun ApprovalHistory(calls: List<InspectorCallRecord>, onSelected: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(DecisionFilter.ALL) }
    val approvals = calls.filter { it.approvalState != null }
    val visible = remember(approvals, filter) {
        approvals.filter {
            when (filter) {
                DecisionFilter.ALL -> true
                DecisionFilter.APPROVED -> it.approvalState == InspectorApprovalState.APPROVED
                DecisionFilter.DENIED -> it.approvalState == InspectorApprovalState.DENIED
                DecisionFilter.TIMED_OUT -> it.approvalState == InspectorApprovalState.TIMED_OUT
            }
        }
    }
    ApprovalSummary(approvals)
    FilterRow(
        entries = listOf(
            DecisionFilter.ALL to "All",
            DecisionFilter.APPROVED to "Approved",
            DecisionFilter.DENIED to "Denied",
            DecisionFilter.TIMED_OUT to "Timed out",
        ),
        selected = filter,
        onSelected = { filter = it },
    )
    HistoryList(
        records = visible,
        title = "APPROVAL HISTORY",
        emptyMessage = if (approvals.isEmpty()) "No approvals in this session." else "No approvals match this filter.",
        approvalMode = true,
        onSelected = onSelected,
    )
}

@Composable
private fun HistoryList(
    records: List<InspectorCallRecord>,
    title: String,
    emptyMessage: String,
    approvalMode: Boolean,
    onSelected: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionLabel(title, if (records.isEmpty()) "" else "Most recent first") }
        if (records.isEmpty()) {
            item { EmptyHistory(emptyMessage) }
        } else {
            items(records, key = { it.call.id }) { record ->
                if (approvalMode) {
                    ApprovalCard(record) { onSelected(record.call.id) }
                } else {
                    CallCard(record) { onSelected(record.call.id) }
                }
            }
        }
    }
}

@Composable
private fun InspectorHeader(
    snapshot: InspectorSnapshot,
    tab: HistoryTab,
    onTabSelected: (HistoryTab) -> Unit,
) {
    Surface(color = SurfaceColor, shadowElevation = 1.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 20.dp, end = 16.dp, bottom = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("App Agent Inspector", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Local session  ·  ${snapshot.appName}",
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ConnectionBadge(snapshot.attached)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                HeaderTab(
                    label = "Calls",
                    count = snapshot.calls.size,
                    selected = tab == HistoryTab.CALLS,
                    onClick = { onTabSelected(HistoryTab.CALLS) },
                    modifier = Modifier.weight(1f),
                )
                HeaderTab(
                    label = "Approvals",
                    count = snapshot.calls.count { it.approvalState != null },
                    selected = tab == HistoryTab.APPROVALS,
                    onClick = { onTabSelected(HistoryTab.APPROVALS) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HeaderTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (selected) Accent else Muted,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) Accent.copy(alpha = 0.10f) else Line.copy(alpha = 0.65f),
            ) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    color = if (selected) Accent else Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) Accent else Color.Transparent),
        )
    }
}

@Composable
private fun ConnectionBadge(attached: Boolean) {
    val color = if (attached) Success else Danger
    Surface(shape = RoundedCornerShape(9.dp), color = color.copy(alpha = 0.10f)) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(
                text = if (attached) "LIVE" else "DETACHED",
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

@Composable
private fun CallSummary(calls: List<InspectorCallRecord>) {
    val approvals = calls.count { it.approvalState != null }
    val issues = calls.count {
        it.callState in setOf(
            InspectorCallState.FAILED,
            InspectorCallState.DENIED,
            InspectorCallState.CANCELLED,
        )
    }
    SummaryRow(
        Triple("SESSION", "${calls.size} calls", Ink),
        Triple("APPROVALS", approvals.toString(), Ink),
        Triple("ISSUES", issues.toString(), if (issues > 0) Danger else Ink),
    )
}

@Composable
private fun ApprovalSummary(calls: List<InspectorCallRecord>) {
    val waiting = calls.count { it.approvalState == InspectorApprovalState.WAITING }
    val approved = calls.count { it.approvalState == InspectorApprovalState.APPROVED }
    val rejected = calls.count {
        it.approvalState in setOf(
            InspectorApprovalState.DENIED,
            InspectorApprovalState.TIMED_OUT,
            InspectorApprovalState.UNAVAILABLE,
            InspectorApprovalState.FAILED,
        )
    }
    SummaryRow(
        Triple("WAITING", waiting.toString(), if (waiting > 0) Warning else Ink),
        Triple("APPROVED", approved.toString(), Success),
        Triple("NOT APPROVED", rejected.toString(), if (rejected > 0) Danger else Ink),
    )
}

@Composable
private fun SummaryRow(vararg values: Triple<String, String, Color>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        values.forEach { (label, value, color) ->
            Column {
                Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                Spacer(Modifier.height(3.dp))
                Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        shape = RoundedCornerShape(10.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, Line),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 13.sp),
            cursorBrush = SolidColor(Accent),
            decorationBox = { input ->
                Box(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                    if (value.isBlank()) Text("Search tool, call ID, or status", color = Muted, fontSize = 13.sp)
                    input()
                }
            },
        )
    }
}

@Composable
private fun <T> FilterRow(entries: List<Pair<T, String>>, selected: T, onSelected: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (value, label) ->
            val active = value == selected
            Surface(
                modifier = Modifier.clickable { onSelected(value) },
                shape = RoundedCornerShape(9.dp),
                color = if (active) Ink else SurfaceColor,
                border = if (active) null else BorderStroke(1.dp, Line),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    color = if (active) Color.White else Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(trailing, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun EmptyHistory(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, Line),
    ) {
        Text(message, modifier = Modifier.padding(16.dp, 28.dp), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun CallCard(record: InspectorCallRecord, onClick: () -> Unit) {
    AuditCard(
        record = record,
        status = { CallStateBadge(record.callState) },
        footer = {
            Text(
                text = "${record.source}  ·  ${record.durationMillis.duration()}",
                modifier = Modifier.weight(1f),
                color = Muted,
                fontSize = 10.sp,
            )
            record.approvalState?.let { ApprovalStateBadge(it) }
            Spacer(Modifier.width(8.dp))
            Text("View", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        },
        onClick = onClick,
    )
}

@Composable
private fun ApprovalCard(record: InspectorCallRecord, onClick: () -> Unit) {
    AuditCard(
        record = record,
        status = { ApprovalStateBadge(requireNotNull(record.approvalState)) },
        footer = {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.approvalSummary(), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "${record.source}  ·  ${record.approvalWaitMillis.duration()}",
                    color = Muted,
                    fontSize = 10.sp,
                )
            }
            Text("Open call", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        },
        onClick = onClick,
    )
}

@Composable
private fun AuditCard(
    record: InspectorCallRecord,
    status: @Composable () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(modifier = Modifier.padding(14.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                status()
                Spacer(Modifier.weight(1f))
                Text(record.receivedAtEpochMillis.shortTime(), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(9.dp))
            Text(
                text = record.call.name,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = record.description ?: "No registered tool description.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Line)
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically, content = footer)
        }
    }
}

@Composable
private fun CallStateBadge(state: InspectorCallState) {
    val color = if (state == InspectorCallState.COMPLETED) Success
    else if (state == InspectorCallState.RUNNING) Accent else Danger
    Badge(state.label, color)
}

@Composable
private fun ApprovalStateBadge(state: InspectorApprovalState) {
    val color = if (state == InspectorApprovalState.APPROVED) Success
    else if (state == InspectorApprovalState.WAITING) Warning else Danger
    Badge(state.label, color)
}

@Composable
private fun Badge(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.10f)) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(color))
            Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DetailHeader(callId: String, onBack: () -> Unit) {
    Surface(color = SurfaceColor, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back", color = Accent, fontWeight = FontWeight.SemiBold) }
            Column(modifier = Modifier.weight(1f)) {
                Text("Tool call", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    callId,
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CopyLabel("Copy ID", callId)
        }
    }
}

@Composable
private fun DetailSummary(record: InspectorCallRecord) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CallStateBadge(record.callState)
                Spacer(Modifier.weight(1f))
                Text(record.durationMillis.duration(), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(11.dp))
            Text(record.call.name, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(record.description ?: "No registered tool description.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun MetadataCard(record: InspectorCallRecord) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(modifier = Modifier.padding(14.dp, 11.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Metadata("SOURCE", record.source, Modifier.weight(1f))
                Metadata("TRANSPORT", record.transport, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Metadata("STARTED", record.receivedAtEpochMillis.fullTime())
            val remaining = record.attributes.filterKeys { it != "source" && it != "transport" }
            if (remaining.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Metadata("ATTRIBUTES", remaining.entries.joinToString { "${it.key}=${it.value}" })
            }
        }
    }
}

@Composable
private fun Metadata(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LifecycleStrip(events: List<InspectorLifecycleEvent>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(modifier = Modifier.padding(13.dp, 11.dp)) {
            Text("LIFECYCLE", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                events.forEachIndexed { index, event ->
                    val color = when (event.tone) {
                        InspectorEventTone.NEUTRAL -> Accent
                        InspectorEventTone.SUCCESS -> Success
                        InspectorEventTone.WARNING -> Warning
                        InspectorEventTone.ERROR -> Danger
                    }
                    Column(Modifier.width(112.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                            if (index < events.lastIndex) {
                                Box(Modifier.width(96.dp).padding(horizontal = 4.dp).height(1.dp).background(Line))
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = event.label,
                            modifier = Modifier.height(24.dp),
                            color = Ink,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("+" + event.elapsedMillis.duration(), color = Muted, fontSize = 8.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, Line),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .testTag(testTag)
                    .padding(14.dp, 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = Muted, fontSize = 10.sp)
                }
                Text(if (expanded) "Hide" else "Show", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            if (expanded) {
                HorizontalDivider(color = Line)
                Box(modifier = Modifier.padding(10.dp)) { content() }
            }
        }
    }
}

@Composable
private fun JsonPayload(payload: String, testTag: String) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        shape = RoundedCornerShape(9.dp),
        color = Code,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(11.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("application/json", modifier = Modifier.weight(1f), color = Color(0xFF9DA6B5), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                Text(
                    "Copy",
                    modifier = Modifier
                        .clickable {
                            coroutineScope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("JSON payload", payload)))
                            }
                        }
                        .padding(5.dp),
                    color = Color(0xFF9DB4FF),
                    fontSize = 9.sp,
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            Text(
                payload,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
                    .padding(11.dp),
                color = Color(0xFFE8EAF0),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ApprovalDetail(record: InspectorCallRecord) {
    val state = record.approvalState
    Column(modifier = Modifier.testTag("approval-detail")) {
        DetailValue("Policy", "mobile", monospace = true)
        DetailValue(
            "Status",
            state?.label ?: "Not requested",
            valueColor = if (state == InspectorApprovalState.APPROVED) Success
            else if (state == InspectorApprovalState.WAITING) Warning
            else Danger,
        )
        DetailValue("Total wait", record.approvalWaitMillis.duration())
        record.approvalResolvedElapsedMillis?.let {
            DetailValue("Resolved at", (record.receivedAtEpochMillis + it).fullTime())
        }
        record.approvalFailureCode?.let {
            DetailValue("Failure code", it, valueColor = Danger, monospace = true)
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, valueColor: Color = Ink, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(0.42f), color = Muted, fontSize = 10.sp)
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CopyLabel(label: String, value: String) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    Text(
        label,
        modifier = Modifier
            .clickable {
                coroutineScope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
                }
            }
            .padding(8.dp),
        color = Accent,
        fontSize = 11.sp,
    )
}

private fun InspectorCallRecord.approvalSummary(): String = when (approvalState) {
    InspectorApprovalState.WAITING -> "No decision yet"
    InspectorApprovalState.APPROVED -> "Approved by application handler"
    InspectorApprovalState.DENIED -> "Denied by application handler"
    InspectorApprovalState.TIMED_OUT -> "Approval timed out"
    InspectorApprovalState.UNAVAILABLE -> "Approval UI unavailable"
    InspectorApprovalState.FAILED -> "Approval flow failed"
    null -> ""
}

private val PrettyJson = Json { prettyPrint = true }
private fun String.prettyJson(): String = runCatching {
    PrettyJson.encodeToString<JsonElement>(ToolJson.parseElement(this))
}.getOrDefault(this)

private val ShortTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
private val FullTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx", Locale.ROOT)
private fun Long.shortTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(ShortTime)
private fun Long.fullTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(FullTime)
private fun Long?.duration(): String = when {
    this == null -> "In progress"
    this < 1_000L -> "$this ms"
    else -> String.format(Locale.ROOT, "%.1f s", this / 1_000.0)
}
