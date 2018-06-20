/*
 * The MIT License
 *
 * Copyright (c) 2018 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package picard.sam.BamErrorMetric;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.reference.SamLocusAndReferenceIterator;
import htsjdk.samtools.reference.SamLocusAndReferenceIterator.SAMLocusAndReference;
import htsjdk.samtools.util.CollectionUtil;
import htsjdk.samtools.util.SamLocusIterator.LocusInfo;
import htsjdk.samtools.util.SamLocusIterator.RecordAndOffset;
import htsjdk.samtools.util.SequenceUtil;
import org.broadinstitute.barclay.argparser.CommandLineParser;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An interface and implementations for classes the look at a collection of bases in reads and computes various ErrorMetrics from them.
 *
 * @author Yossi Farjoun
 */

public class BaseErrorCalculation {

    /**
     * An interface that can take a collection of bases (provided as {@link htsjdk.samtools.util.SamLocusIterator.RecordAndOffset RecordAndOffset}
     * and {@link SamLocusAndReferenceIterator.SAMLocusAndReference SAMLocusAndReference}) and generates a
     * {@link ErrorMetrics.ErrorMetricBase} from them.
     * <p>
     * The Calculator has a suffix which will be used to generate the metric file suffixes.
     */
    public interface BaseCalculator {

        /**
         * The suffix that pertains to the implementation of aggregation
         **/
        String getSuffix();

        /**
         * Returns the metric generated by the observed loci
         **/
        ErrorMetrics.ErrorMetricBase getMetric();

        /**
         * the function by which new loci are "shown" to the calculator
         **/
        void addBase(final RecordAndOffset recordAndOffset,
                     final SamLocusAndReferenceIterator.SAMLocusAndReference locusInfo);
    }

    /**
     * An enum that is used to generate a {@link Supplier<BaseErrorCalculator>} from a string
     * To use this given a String 'str':
     * <p>
     * Errors.valueOf(str).getErrorSupplier()
     * <p>
     * This is used in {@link CollectBamErrorMetrics} to convert an input argument to a {@link BaseErrorAggregation}.
     */
    enum Errors implements CommandLineParser.ClpEnum {
        ERROR(SimpleErrorCalculator::new, "Collects the average error at the bases provided."),
        OVERLAPPING_ERROR(OverlappingReadsErrorCalculator::new, "Only considers bases from the overlapping parts of reads from the same template. " +
                "For those bases, it calculates the error that can be attributable to pre-sequencing, versus during-sequencing.");

        private final Supplier<? extends BaseCalculator> errorSupplier;

        Errors(Supplier<? extends BaseCalculator> errorSupplier, final String docString) {
            this.errorSupplier = errorSupplier;
            this.docString = docString;
        }

        public Supplier<? extends BaseCalculator> getErrorSupplier() {
            return errorSupplier;
        }

        private final String docString;

        @Override
        public String getHelpDoc() {
            return docString + " Suffix is: '" + errorSupplier.get().getSuffix() + "'.";
        }
    }

    public abstract static class BaseErrorCalculator implements BaseCalculator {
        long totalBases = 0;

        /**
         * the function by which new loci are "shown" to the calculator
         **/
        @Override
        public void addBase(final RecordAndOffset recordAndOffset, final SAMLocusAndReference locusAndRef) {
            final byte readBase = recordAndOffset.getReadBase();
            if (!SequenceUtil.isNoCall(readBase)) {
                totalBases++;
            }
        }
    }

    /**
     * A calculator that estimates the error rate of the bases it observes, assuming that the reference is truth.
     */
    public static class SimpleErrorCalculator extends BaseErrorCalculator {

        protected long nMismatchingBases = 0;

        /**
         * The function by which new loci are "shown" to the calculator
         **/
        @Override
        public void addBase(final RecordAndOffset recordAndOffset, final SAMLocusAndReference locusAndRef) {
            super.addBase(recordAndOffset, locusAndRef);
            final byte readBase = recordAndOffset.getReadBase();
            if (!SequenceUtil.isNoCall(readBase) && (readBase != locusAndRef.getReferenceBase())) {
                nMismatchingBases++;
            }
        }

        /**
         * The suffix that pertains to the implementation of aggregation
         **/
        @Override
        public String getSuffix() {
            return "error";
        }

        /**
         * Returns the metric generated by the observed loci
         **/
        @Override
        public ErrorMetrics.SimpleErrorMetric getMetric() {
            return new ErrorMetrics.SimpleErrorMetric("", totalBases, nMismatchingBases);
        }
    }

    /**
     * A calculator that estimates the error rate of the bases it observes, assuming that the reference is truth.
     * This calculator only includes bases that have been read twice in the same template (once from each read) and
     * thus only includes bases that arise from the overlapping part of the reads. Over those bases the Calculator
     * distinguishes between whether the two reads agree with each other but differ from the reference (indicative
     * of a difference between the template and the reference, and when one of the reads agrees with the reference
     * but the other does not which indicates that there might have been a sequencing error in that read.
     */
    public static class OverlappingReadsErrorCalculator extends BaseErrorCalculator {
        long nBothDisagreeWithReference = 0;
        long nDisagreeWithRefAndMate = 0;
        long nThreeWaysDisagreement = 0;
        long nTotalBasesWithOverlappingReads = 0;

        static int currentPosition = 0;
        static final Map<String, Set<RecordAndOffset>> readNameSets = new CollectionUtil.DefaultingMap<>(s -> new HashSet<>(), true);

        private static void updateReadNameSet(final LocusInfo locusInfo) {
            if (locusInfo.getPosition() == currentPosition) {
                return;
            }
            readNameSets.clear();
            locusInfo.getRecordAndOffsets().forEach(r -> readNameSets.get(r.getReadName()).add(r));
            currentPosition = locusInfo.getPosition();
        }

        /**
         * The function by which new loci are "shown" to the calculator
         **/
        @Override
        public void addBase(final RecordAndOffset recordAndOffset, final SAMLocusAndReference locusAndRef) {
            super.addBase(recordAndOffset, locusAndRef);
            final byte readBase = recordAndOffset.getReadBase();
            final SAMRecord record = recordAndOffset.getRecord();

            // by traversing the reads and splitting into sets with the same name we convert a O(N^2) iteration
            // into a O(N) iteration
            updateReadNameSet(locusAndRef.getLocus());

            final RecordAndOffset mate = readNameSets.get(record.getReadName())
                    .stream()
                    .filter(putative -> areReadsMates(record, putative.getRecord()))
                    .findFirst()
                    .orElse(null);

            // we are only interested in bases for which the mate read also has a base over the same locus
            if (mate == null) return;
            // both bases need to be called for this error calculation

            final byte mateBase = mate.getReadBase();
            if (SequenceUtil.isNoCall(readBase)) return;
            if (SequenceUtil.isNoCall(mateBase)) return;

            nTotalBasesWithOverlappingReads++;

            // Only bases that disagree with the reference are counted as errors.
            if (SequenceUtil.basesEqual(readBase, locusAndRef.getReferenceBase())) return;

            final boolean agreesWithMate = SequenceUtil.basesEqual(readBase, mateBase);
            final boolean mateAgreesWithRef = SequenceUtil.basesEqual(mateBase, locusAndRef.getReferenceBase());

            if (agreesWithMate) {
                nBothDisagreeWithReference++;
            } else if (mateAgreesWithRef) {
                nDisagreeWithRefAndMate++;
            } else {
                nThreeWaysDisagreement++;
            }
        }

        /**
         * The suffix that pertains to the implementation of aggregation
         **/
        @Override
        public String getSuffix() {
            return "overlapping_error";
        }

        /**
         * Returns the metric generated by the observed loci
         **/
        @Override
        public ErrorMetrics.OverlappingErrorMetric getMetric() {
            return new ErrorMetrics.OverlappingErrorMetric("", totalBases, nTotalBasesWithOverlappingReads, nDisagreeWithRefAndMate,
                    nBothDisagreeWithReference, nThreeWaysDisagreement);
        }

        private boolean areReadsMates(final SAMRecord read1, final SAMRecord read2) {
            // must have same name
            if (!read1.getReadName().equals(read2.getReadName())) return false;
            // must be paired
            if (!read1.getReadPairedFlag()) return false;
            // one must be first while the other is not
            if (!read1.getFirstOfPairFlag() ^ read2.getFirstOfPairFlag()) return false;
            // one must be second while the other is not
            if (!read1.getSecondOfPairFlag() ^ read2.getSecondOfPairFlag()) return false;
            // read1 must be mapped
            if (read1.getReadUnmappedFlag()) return false;
            // read2 must be mapped
            if (read2.getReadUnmappedFlag()) return false;
            // read1 must be non-secondary
            if (read1.isSecondaryAlignment()) return false;
            // read2 must be non-secondary
            if (read2.isSecondaryAlignment()) return false;

            return read1.getMateAlignmentStart() == read2.getAlignmentStart();
        }
    }
}
