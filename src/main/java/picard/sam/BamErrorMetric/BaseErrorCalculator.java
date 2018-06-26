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

import htsjdk.samtools.reference.SamLocusAndReferenceIterator;
import htsjdk.samtools.util.SamLocusIterator;
import htsjdk.samtools.util.SequenceUtil;

public abstract class BaseErrorCalculator implements BaseCalculator {
    long totalBases = 0;

    /**
     * the function by which new loci are "shown" to the calculator
     **/
    @Override
    public void addBase(final SamLocusIterator.RecordAndOffset recordAndOffset, final SamLocusAndReferenceIterator.SAMLocusAndReference locusAndRef) {
        final byte readBase = recordAndOffset.getReadBase();
        if (!SequenceUtil.isNoCall(readBase)) {
            totalBases++;
        }
    }
}
