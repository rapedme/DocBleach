package xyz.docbleach.module.ole2;

import org.apache.poi.hpsf.*;
import org.apache.poi.poifs.filesystem.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.docbleach.api.BleachSession;
import xyz.docbleach.api.bleach.Bleach;
import xyz.docbleach.api.exception.BleachException;
import xyz.docbleach.api.threat.Threat;
import xyz.docbleach.api.threat.ThreatAction;
import xyz.docbleach.api.threat.ThreatSeverity;
import xyz.docbleach.api.threat.ThreatType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.function.Predicate;


/**
 * Sanitizes an OLE2 file (.doc, .xls, .ppt) by copying its elements into a new OLE2 container.
 * Information to be modified (template, ...) are changed on the fly, and entries to be removed are
 * just not copied over. This way, using a simple Visitor, it is possible to add rules applied on
 * each entry.
 */
public class OLE2Bleach implements Bleach {
    private static final Logger LOGGER = LoggerFactory.getLogger(OLE2Bleach.class);
    private static final String MACRO_ENTRY = "Macros";
    private static final String VBA_ENTRY = "VBA";
    private static final String NORMAL_TEMPLATE = "Normal.dotm";

    @Override
    public boolean handlesMagic(InputStream stream) {
        try {
            return NPOIFSFileSystem.hasPOIFSHeader(stream);
        } catch (IOException e) {
            LOGGER.warn("An exception occured", e);
            return false;
        }
    }

    @Override
    public String getName() {
        return "OLE2 Bleach";
    }

    @Override
    public void sanitize(InputStream inputStream, OutputStream outputStream, BleachSession session) throws BleachException {
        try (
                NPOIFSFileSystem fsIn = new NPOIFSFileSystem(inputStream);
                NPOIFSFileSystem fs = new NPOIFSFileSystem()
        ) {
            DirectoryEntry rootIn = fsIn.getRoot();
            DirectoryEntry root = fs.getRoot();

            LOGGER.debug("Entries before: {}", rootIn.getEntryNames());
            // Save the changes to a new file

            // Returns false if the entry should be removed
            Predicate<Entry> visitor = ((Predicate<Entry>) (e -> true))
                    .and(removeMacros(session))
                    .and(removeTemplate(session));

            rootIn.getEntries().forEachRemaining(entry -> {
                if (!visitor.test(entry)) {
                    return;
                }
                copyNodesRecursively(entry, root);
            });

            LOGGER.debug("Entries after: {}", root.getEntryNames());
            // Save the changes to a new file

            fs.writeFilesystem(outputStream);
        } catch (IOException | IndexOutOfBoundsException e) {
            throw new BleachException(e);
        }
    }

    private void copyNodesRecursively(Entry entry, DirectoryEntry destination) {
        try {
            EntryUtils.copyNodeRecursively(entry, destination);
        } catch (IOException e) {
            LOGGER.error("An error occured while trying to recursively copy nodes", e);
        }
    }

    Predicate<Entry> removeTemplate(BleachSession session) {
        return entry -> {
            String entryName = entry.getName();
            if (!SummaryInformation.DEFAULT_STREAM_NAME.equals(entryName)) {
                return true;
            }

            if (!(entry instanceof DocumentEntry)) {
                return true;
            }

            DocumentEntry dsiEntry = (DocumentEntry) entry;
            sanitizeDocumentEntry(session, dsiEntry);

            return true;
        };
    }

    void sanitizeDocumentEntry(BleachSession session, DocumentEntry dsiEntry) {
        try (DocumentInputStream dis = new DocumentInputStream(dsiEntry)) {
            PropertySet ps = new PropertySet(dis);
            SummaryInformation dsi = new SummaryInformation(ps);
            sanitizeSummaryInformation(session, dsi);
        } catch (NoPropertySetStreamException | UnexpectedPropertySetTypeException | MarkUnsupportedException | IOException e) {
            LOGGER.error("An error occured while trying to sanitize the document entry", e);
        }
    }

    private void sanitizeSummaryInformation(BleachSession session, SummaryInformation dsi) {
        sanitizeTemplate(session, dsi);
        sanitizeComments(session, dsi);
    }

    private void sanitizeComments(BleachSession session, SummaryInformation dsi) {
        String comments = dsi.getComments();
        if (comments == null || comments.isEmpty())
            return;

        LOGGER.trace("Removing the document's Comments (was '{}')", comments);

        dsi.removeComments();
        Threat threat = new Threat(ThreatType.UNRECOGNIZED_CONTENT,
                ThreatSeverity.LOW,
                "Summary Information - Comment",
                "Comment was: '" + comments + "'",
                ThreatAction.REMOVE);
        session.recordThreat(threat);
    }

    private void sanitizeTemplate(BleachSession session, SummaryInformation dsi) {
        String template = dsi.getTemplate();
        if (NORMAL_TEMPLATE.equals(template))
            return;

        if (template == null)
            return;

        LOGGER.trace("Removing the document's template (was '{}')", template);
        dsi.removeTemplate();

        ThreatSeverity severity = isExternalTemplate(template) ? ThreatSeverity.HIGH : ThreatSeverity.LOW;

        Threat threat = new Threat(ThreatType.EXTERNAL_CONTENT,
                severity,
                "Summary Information - Template",
                "Template was: '" + template + "'",
                ThreatAction.REMOVE);

        session.recordThreat(threat);
    }

    boolean isExternalTemplate(String template) {
        return template.startsWith("http://") ||
                template.startsWith("https://") ||
                template.startsWith("ftp://");
    }

    Predicate<Entry> removeMacros(BleachSession session) {
        return entry -> {
            String entryName = entry.getName();

            boolean isMacros = MACRO_ENTRY.equalsIgnoreCase(entryName) ||
                    entryName.contains(VBA_ENTRY);

            // Matches _VBA_PROJECT_CUR, VBA, ... :)
            if (!isMacros) {
                return true;
            }

            LOGGER.info("Found Macros, removing them.");
            StringBuilder infos = new StringBuilder();
            if (entry instanceof DirectoryEntry) {
                Set<String> entryNames = ((DirectoryEntry) entry).getEntryNames();
                LOGGER.trace("Macros' entries: {}", entryNames);
                infos.append("Entries: ").append(entryNames);
            } else if (entry instanceof DocumentEntry) {
                int size = ((DocumentEntry) entry).getSize();
                infos.append("Size: ").append(size);
            }

            Threat threat = new Threat(ThreatType.ACTIVE_CONTENT,
                    ThreatSeverity.EXTREME,
                    entryName,
                    infos.toString(),
                    ThreatAction.REMOVE);
            session.recordThreat(threat);

            return false;
        };
    }
}