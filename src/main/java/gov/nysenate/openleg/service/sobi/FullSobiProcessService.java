package gov.nysenate.openleg.service.sobi;

import com.google.common.collect.ImmutableMap;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.sobi.SobiDao;
import gov.nysenate.openleg.model.sobi.SobiFile;
import gov.nysenate.openleg.model.sobi.SobiFragment;
import gov.nysenate.openleg.model.sobi.SobiFragmentType;
import gov.nysenate.openleg.model.sobi.SobiLineType;
import gov.nysenate.openleg.service.agenda.AgendaProcessor;
import gov.nysenate.openleg.service.agenda.AgendaVoteProcessor;
import gov.nysenate.openleg.service.base.SobiProcessor;
import gov.nysenate.openleg.service.bill.BillProcessor;
import gov.nysenate.openleg.service.calendar.ActiveListProcessor;
import gov.nysenate.openleg.service.calendar.CalendarProcessor;
import gov.nysenate.openleg.service.entity.CommitteeProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This SobiProcessService implementation processes every type of sobi fragment.
 */
@Service
public class FullSobiProcessService implements SobiProcessService
{
    private static final Logger logger = LoggerFactory.getLogger(FullSobiProcessService.class);

    @Autowired
    private SobiDao sobiDao;

    /** --- Processor Dependencies --- */

    @Autowired
    private AgendaProcessor agendaProcessor;
    @Autowired
    private AgendaVoteProcessor agendaVoteProcessor;
    @Autowired
    private BillProcessor billProcessor;
    @Autowired
    private CalendarProcessor calendarProcessor;
    @Autowired
    private ActiveListProcessor activeListProcessor;
    @Autowired
    private CommitteeProcessor committeeProcessor;

    /** Register processors to handle a specific SobiFragment via this mapping. */
    private ImmutableMap<SobiFragmentType, SobiProcessor> processorMap;

    @PostConstruct
    protected void init() {
        processorMap = ImmutableMap.<SobiFragmentType, SobiProcessor>builder()
            .put(SobiFragmentType.BILL, billProcessor)
            .put(SobiFragmentType.CALENDAR, calendarProcessor)
            .put(SobiFragmentType.CALENDAR_ACTIVE, activeListProcessor)
            .put(SobiFragmentType.COMMITTEE, committeeProcessor)
            .build();
    }

    /** --- Implemented Methods --- */

    /** {@inheritDoc} */
    @Override
    public int collateSobiFiles() {
        try {
            int totalCollated = 0;
            List<SobiFile> newSobis;
            do {
                // Iterate through all the new sobi files in small batches to avoid saturating memory.
                newSobis = sobiDao.getIncomingSobiFiles(SortOrder.ASC, LimitOffset.HUNDRED);
                logger.debug((newSobis.isEmpty()) ? "No more sobi files to collate."
                                                  : "Collating {} sobi files.", newSobis.size());
                for (SobiFile sobiFile : newSobis) {
                    List<SobiFragment> fragments = createFragments(sobiFile);
                    // Record the sobi file in the backing store.
                    sobiDao.updateSobiFile(sobiFile);
                    // Save the extracted fragments. They will be marked as pending processing.
                    for (SobiFragment fragment : fragments) {
                        logger.debug("Saving fragment {}", fragment);
                        fragment.setPendingProcessing(true);
                        sobiDao.updateSobiFragment(fragment);
                    }
                    // Done with this sobi file so let's archive it.
                    sobiDao.archiveSobiFile(sobiFile);
                    totalCollated++;
                }
            }
            while (!newSobis.isEmpty());
            return totalCollated;
        }
        catch (IOException ex) {
            logger.error("Error while retrieving incoming sobi files during collation.", ex);
        }
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public List<SobiFragment> getPendingFragments(SortOrder sortByPubDate, LimitOffset limitOffset) {
        return sobiDao.getPendingSobiFragments(sortByPubDate, limitOffset);
    }

    /** {@inheritDoc} */
    @Override
    public void processFragments(List<SobiFragment> fragments) {
        logger.info((fragments.isEmpty()) ? "No more fragments to process"
                                          : "Processing {} fragments", fragments.size());
        for (SobiFragment fragment : fragments) {
            if (processorMap.containsKey(fragment.getType())) {
                processorMap.get(fragment.getType()).process(fragment);
            }
            else {
                logger.error("No processors have been registered to handle: " + fragment);
                // TODO: Figure out what to do here.
            }
            markFragmentAsProcessed(fragment, new Date());
            sobiDao.updateSobiFragment(fragment);
        }
    }

    /** {@inheritDoc}
     *
     *  Perform the operation in small batches so memory is not saturated.
     */
    @Override
    public void processPendingFragments() {
        List<SobiFragment> fragments;
        do {
            fragments = getPendingFragments(SortOrder.ASC, LimitOffset.HUNDRED);
            processFragments(fragments);
        }
        while (!fragments.isEmpty());
    }

    /** {@inheritDoc} */
    @Override
    public void updatePendingProcessing(String fragmentId, boolean pendingProcessing)
                                        throws SobiFragmentNotFoundEx {
        try {
            SobiFragment fragment = sobiDao.getSobiFragment(fragmentId);
            fragment.setPendingProcessing(pendingProcessing);
            sobiDao.updateSobiFragment(fragment);
        }
        catch (DataAccessException ex) {
            throw new SobiFragmentNotFoundEx();
        }
    }

    /** --- Internal Methods --- */

    /**
     * Extracts a list of SobiFragments from the given SobiFile.
     */
    private List<SobiFragment> createFragments(SobiFile sobiFile) throws IOException {
        List<SobiFragment> sobiFragments = new ArrayList<>();
        StringBuilder billBuffer = new StringBuilder();

        // Incrementing sequenceNo maintains the order in which the sobi fragments were
        // found in the source sobiFile. However the sequence number for the bill fragment
        // is always set to 0 to ensure that they are always processed first.
        int sequenceNo = 1;

        List<String> lines = Arrays.asList(sobiFile.getText().split("\\r?\\n"));
        Iterator<String> lineIterator = lines.iterator();
        while (lineIterator.hasNext()) {
            String line = lineIterator.next();
            SobiFragmentType fragmentType = getFragmentTypeFromLine(line);
            if (fragmentType != null) {
                // Bill fragments are in the sobi format and appended into a single buffer
                if (fragmentType.equals(SobiFragmentType.BILL)) {
                    // Memos need to be converted to latin1 encoding
                    if (line.charAt(11) == SobiLineType.SPONSOR_MEMO.getTypeCode()) {
                        line = new String(line.getBytes(sobiFile.getEncoding()), "latin1");
                    }
                    line = line.replace((char)193, '°');
                    billBuffer.append(line).append("\n");
                }
                // Other fragment types are in XML format. The iterator moves past the closing xml
                // tag and the xml text is stored in the fragment.
                else {
                    String xmlText = extractXmlText(fragmentType, line, lineIterator);
                    SobiFragment fragment = new SobiFragment(sobiFile, fragmentType, xmlText, sequenceNo++);
                    sobiFragments.add(fragment);
                }
            }
        }
        // Convert the billBuffer into a single bill fragment (if applicable) with sequence no set to 0.
        if (billBuffer.length() > 0) {
            SobiFragment billFragment = new SobiFragment(sobiFile, SobiFragmentType.BILL, billBuffer.toString(), 0);
            sobiFragments.add(billFragment);
        }
        return sobiFragments;
    }

    /**
     * Applies process completion details to the given SobiFragment.
     */
    private void markFragmentAsProcessed(SobiFragment fragment, Date processedDate) {
        fragment.setPendingProcessing(false);
        fragment.setProcessedCount(fragment.getProcessedCount() + 1);
        fragment.setProcessedDateTime(processedDate);
    }

    /**
     * Check the given SOBI line to determine if it matches the start of a SOBI Fragment type.
     *
     * @param line String
     * @return SobiFragmentType or null if no match
     */
    private SobiFragmentType getFragmentTypeFromLine(String line) {
        for (SobiFragmentType fragmentType : SobiFragmentType.values()) {
            if (line.matches(fragmentType.getStartPattern())) {
                return fragmentType;
            }
        }
        return null;
    }

    /**
     * Extracts a well formed XML document from the lines and writes it to the given
     * file. This depends strongly on escape sequences being on their own line; otherwise
     * we'll get malformed XML docs.
     *
     * @param fragmentType SobiFragmentType
     * @param line String - The starting line of the document
     * @param iterator Iterator<String> - Current iterator from the sobi file's text body
     *
     * @return String - The resulting XML string.
     * @throws java.io.IOException
     */
    private String extractXmlText(SobiFragmentType fragmentType, String line, Iterator<String> iterator) throws IOException {
        String endPattern = fragmentType.getEndPattern();
        StringBuffer xmlBuffer = new StringBuffer(
            "<?xml version='1.0' encoding='UTF-8'?>&newl;" +
                "<SENATEDATA>&newl;" + line + "&newl;"
        );
        String in = null;
        while (iterator.hasNext()) {
            in = iterator.next();
            xmlBuffer.append(in.replaceAll("\\xb9", "&sect;")).append("&newl;");
            if (in.matches(endPattern)) {
                break;
            }
        }
        if (in == null) {
            // This is bad, but don't throw an exception. If the resulting XML document
            // is malformed we'll throw the exception during ingest.
            logger.error("Unterminated XML document: " + line);
        }
        String xmlString = xmlBuffer.append("</SENATEDATA>").toString();

        // TODO: Figure out this magic.
        xmlBuffer = new StringBuffer();
        Matcher m = Pattern.compile("<\\!\\[CDATA\\[(.*?)\\]\\]>").matcher(xmlString);
        while(m.find()) {
            m.appendReplacement(xmlBuffer, Matcher.quoteReplacement(m.group(0).replaceAll("&newl;", "").replaceAll("\\\\n","\n")));
        }
        m.appendTail(xmlBuffer);

        // TODO: Figure out this magic as well.
        xmlString = xmlBuffer.toString().replaceAll("&newl;", "\n").replaceAll("(?!\n)\\p{Cntrl}","").replaceAll("(?!\\.{2})[ ]{2,}"," ");
        return xmlString;
    }
}