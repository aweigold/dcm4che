/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4che.net.service;

import java.io.IOException;
import java.util.HashSet;

import org.dcm4che.data.Attributes;
import org.dcm4che.data.Tag;
import org.dcm4che.net.Association;
import org.dcm4che.net.DimseRSPHandler;
import org.dcm4che.net.NoPresentationContextException;
import org.dcm4che.net.pdu.PresentationContext;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
public class CStoreRQWriter {

    private final DataWriterFactory factory;
    private volatile boolean canceled;

    public CStoreRQWriter(DataWriterFactory factory) {
         if (factory == null)
             throw new NullPointerException();

         this.factory = factory;
    }

    public void cancel() {
        canceled = true;
    }

    public void write(Association as, PresentationContext pc,
            int priority, String moveOriginatorAET, int moveOriginatorMsgId,
            InstanceRefs instRefs, final Progress progress)
            throws IOException, InterruptedException {
        
        final HashSet<String> waitingForRSP = new HashSet<String>();
        for (String[] instRef : instRefs) {
            if (canceled) {
                waitForRSP(waitingForRSP);
                progress.canceled();
                return;
            }

            final String iuid = instRef[0];
            final String cuid = instRef[1];
            final String tsuid = instRef[2];
            final String uri = instRef[3];
            DimseRSPHandler rspHandler =
                new DimseRSPHandler(as.nextMessageID()) {

                @Override
                public void onDimseRSP(Association as, Attributes cmd,
                        Attributes data) {
                    super.onDimseRSP(as, cmd, data);
                    progress.onCStoreRSP(iuid, cmd.getInt(Tag.Status, -1));
                    noLongerWaitForRSP(waitingForRSP, iuid);
                }

                @Override
                public void onClose(Association as) {
                    super.onClose(as);
                    synchronized (waitingForRSP) {
                        waitingForRSP.clear();
                        waitingForRSP.notifyAll();
                    }
                }
            };
            synchronized (waitingForRSP) {
                waitingForRSP.add(iuid);
            }
            try {
                as.cstore(cuid, iuid, priority, moveOriginatorAET,
                        moveOriginatorMsgId, factory.createDataWriter(uri),
                        tsuid, rspHandler);
            } catch (NoPresentationContextException e) {
                progress.noPresentationContextFor(iuid, cuid, tsuid);
                noLongerWaitForRSP(waitingForRSP, iuid);
            }
        }
        waitForRSP(waitingForRSP);
        progress.done();
    }

    private static void waitForRSP(HashSet<String> waitingForRSP)
            throws InterruptedException {
        synchronized (waitingForRSP) {
            while (!waitingForRSP.isEmpty())
                waitingForRSP.wait();
        }
    }

    private void noLongerWaitForRSP(HashSet<String> waitingForRSP, String iuid) {
        synchronized (waitingForRSP) {
            waitingForRSP.remove(iuid);
            waitingForRSP.notifyAll();
        }
    }

    public interface Progress {
        void noPresentationContextFor(String iuid, String cuid, String tsuid);
        void onCStoreRSP(String iuid, int status);
        void canceled();
        void done();
    }

}