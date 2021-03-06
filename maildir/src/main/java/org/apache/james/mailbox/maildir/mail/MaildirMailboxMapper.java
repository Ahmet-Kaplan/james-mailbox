/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.mailbox.maildir.mail;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.MailboxExistsException;
import org.apache.james.mailbox.exception.MailboxNotFoundException;
import org.apache.james.mailbox.maildir.MaildirFolder;
import org.apache.james.mailbox.maildir.MaildirMessageName;
import org.apache.james.mailbox.maildir.MaildirStore;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxConstants;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.store.mail.MailboxMapper;
import org.apache.james.mailbox.store.mail.model.Mailbox;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailbox;
import org.apache.james.mailbox.store.transaction.NonTransactionalMapper;

public class MaildirMailboxMapper extends NonTransactionalMapper implements MailboxMapper<Integer> {

    /**
     * The {@link MaildirStore} the mailboxes reside in
     */
    private final MaildirStore maildirStore;
    
    /**
     * A request-scoped list of mailboxes in order to refer to them via id
     */
    private ArrayList<Mailbox<Integer>> mailboxCache = new ArrayList<Mailbox<Integer>>();

    private final MailboxSession session;
    
    public MaildirMailboxMapper(MaildirStore maildirStore, MailboxSession session) {
        this.maildirStore = maildirStore;
        this.session = session;
    }

    /**
     * @see org.apache.james.mailbox.store.mail.MailboxMapper#delete(org.apache.james.mailbox.store.mail.model.Mailbox)
     */
    @Override
    public void delete(Mailbox<Integer> mailbox) throws MailboxException {
        
        String folderName = maildirStore.getFolderName(mailbox);
        File folder = new File(folderName);
        if (folder.isDirectory()) {
            try {
                if (mailbox.getName().equals(MailboxConstants.INBOX)) {
                    // We must only delete cur, new, tmp and metadata for top INBOX mailbox.
                    FileUtils.deleteDirectory(new File(folder, MaildirFolder.CUR));
                    FileUtils.deleteDirectory(new File(folder, MaildirFolder.NEW));
                    FileUtils.deleteDirectory(new File(folder, MaildirFolder.TMP));
                    File uidListFile = new File(folder, MaildirFolder.UIDLIST_FILE);
                    uidListFile.delete();
                    File validityFile = new File(folder, MaildirFolder.VALIDITY_FILE);
                    validityFile.delete();
                }
                else {
                    // We simply delete all the folder for non INBOX mailboxes.
                    FileUtils.deleteDirectory(folder);
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new MailboxException("Unable to delete Mailbox " + mailbox, e);
            }
        }
        else
            throw new MailboxNotFoundException(mailbox.getName());
    }

   
    /**
     * @see org.apache.james.mailbox.store.mail.MailboxMapper#findMailboxByPath(org.apache.james.mailbox.model.MailboxPath)
     */
    @Override
    public Mailbox<Integer> findMailboxByPath(MailboxPath mailboxPath)
            throws MailboxException, MailboxNotFoundException {      
        Mailbox<Integer> mailbox = maildirStore.loadMailbox(session, mailboxPath);
        return cacheMailbox(mailbox);
    }
    
    /**
     * @see org.apache.james.mailbox.store.mail.MailboxMapper#findMailboxWithPathLike(org.apache.james.mailbox.model.MailboxPath)
     */
    @Override
    public List<Mailbox<Integer>> findMailboxWithPathLike(MailboxPath mailboxPath)
            throws MailboxException {
        final Pattern searchPattern = Pattern.compile("[" + MaildirStore.maildirDelimiter + "]"
                + mailboxPath.getName().replace(".", "\\.").replace(MaildirStore.WILDCARD, ".*"));
        FilenameFilter filter = MaildirMessageName.createRegexFilter(searchPattern);
        File root = maildirStore.getMailboxRootForUser(mailboxPath.getUser());
        File[] folders = root.listFiles(filter);
        ArrayList<Mailbox<Integer>> mailboxList = new ArrayList<Mailbox<Integer>>();
        for (File folder : folders)
            if (folder.isDirectory()) {
                Mailbox<Integer> mailbox = maildirStore.loadMailbox(session, root, mailboxPath.getNamespace(), mailboxPath.getUser(), folder.getName());
                mailboxList.add(cacheMailbox(mailbox));
            }
        // INBOX is in the root of the folder
        if (Pattern.matches(mailboxPath.getName().replace(MaildirStore.WILDCARD, ".*"), MailboxConstants.INBOX)) {
            Mailbox<Integer> mailbox = maildirStore.loadMailbox(session, root, mailboxPath.getNamespace(), mailboxPath.getUser(), "");
            mailboxList.add(0, cacheMailbox(mailbox));
        }
        return mailboxList;
    }

    /**
     * @see org.apache.james.mailbox.store.mail.MailboxMapper#hasChildren(org.apache.james.mailbox.store.mail.model.Mailbox, char)
     */
    @Override
    public boolean hasChildren(Mailbox<Integer> mailbox, char delimiter) throws MailboxException, MailboxNotFoundException {
        String searchString = mailbox.getName() + MaildirStore.maildirDelimiter + MaildirStore.WILDCARD;
        List<Mailbox<Integer>> mailboxes = findMailboxWithPathLike(
                new MailboxPath(mailbox.getNamespace(), mailbox.getUser(), searchString));
        return (mailboxes.size() > 0);
    }

    /**
     * @see org.apache.james.mailbox.store.mail.MailboxMapper#save(org.apache.james.mailbox.store.mail.model.Mailbox)
     */
    @Override
    public void save(Mailbox<Integer> mailbox) throws MailboxException {
        try {
            Mailbox<Integer> originalMailbox = getCachedMailbox(mailbox.getMailboxId());
            MaildirFolder folder = maildirStore.createMaildirFolder(mailbox);
            // equals with null check
            if (originalMailbox.getName() == null ? mailbox.getName() != null : !originalMailbox.getName().equals(mailbox.getName())) {
                if (folder.exists())
                    throw new MailboxExistsException(mailbox.getName());
                
                MaildirFolder originalFolder = maildirStore.createMaildirFolder(originalMailbox);
                // renaming the INBOX means to move its contents to the new folder 
                if (originalMailbox.getName().equals(MailboxConstants.INBOX)) {
                    try {
                        File inboxFolder = originalFolder.getRootFile();
                        File newFolder = folder.getRootFile();
                        if (!newFolder.mkdirs())
                            throw new IOException("Could not create folder " + newFolder);
                        if (!originalFolder.getCurFolder().renameTo(folder.getCurFolder()))
                            throw new IOException("Could not rename folder " + originalFolder.getCurFolder() + " to " + folder.getCurFolder());
                        if (!originalFolder.getNewFolder().renameTo(folder.getNewFolder()))
                            throw new IOException("Could not rename folder " + originalFolder.getNewFolder() + " to " + folder.getNewFolder());
                        if (!originalFolder.getTmpFolder().renameTo(folder.getTmpFolder()))
                            throw new IOException("Could not rename folder " + originalFolder.getTmpFolder() + " to " + folder.getTmpFolder());
                        File oldUidListFile = new File(inboxFolder, MaildirFolder.UIDLIST_FILE);
                        File newUidListFile = new File(newFolder, MaildirFolder.UIDLIST_FILE);
                        if (!oldUidListFile.renameTo(newUidListFile))
                            throw new IOException("Could not rename file " + oldUidListFile + " to " + newUidListFile);
                        File oldValidityFile = new File(inboxFolder, MaildirFolder.VALIDITY_FILE);
                        File newValidityFile = new File(newFolder, MaildirFolder.VALIDITY_FILE);
                        if (!oldValidityFile.renameTo(newValidityFile))
                            throw new IOException("Could not rename file " + oldValidityFile + " to " + newValidityFile);
                        // recreate the INBOX folders, uidvalidity and uidlist will
                        // automatically be recreated later
                        if (!originalFolder.getCurFolder().mkdir())
                            throw new IOException("Could not create folder " + originalFolder.getCurFolder());
                        if (!originalFolder.getNewFolder().mkdir())
                            throw new IOException("Could not create folder " + originalFolder.getNewFolder());
                        if (!originalFolder.getTmpFolder().mkdir())
                            throw new IOException("Could not create folder " + originalFolder.getTmpFolder());
                    } catch (IOException e) {
                        throw new MailboxException("Failed to save Mailbox " + mailbox, e);
                    }
                }
                else {
                    if (!originalFolder.getRootFile().renameTo(folder.getRootFile()))
                        throw new MailboxException("Failed to save Mailbox " + mailbox, 
                                new IOException("Could not rename folder " + originalFolder));
                }
            }
            folder.setACL(session, mailbox.getACL());
        } catch (MailboxNotFoundException e) {
            // it cannot be found and is thus new
            MaildirFolder folder = maildirStore.createMaildirFolder(mailbox);
            if (!folder.exists()) {
                boolean success = folder.getRootFile().exists();
                if (!success) success = folder.getRootFile().mkdirs();
                if (!success)
                    throw new MailboxException("Failed to save Mailbox " + mailbox);
                success = folder.getCurFolder().mkdir();
                success = success && folder.getNewFolder().mkdir();
                success = success && folder.getTmpFolder().mkdir();
                if (!success)
                    throw new MailboxException("Failed to save Mailbox " + mailbox, new IOException("Needed folder structure can not be created"));

            }
            try {
                folder.setUidValidity(mailbox.getUidValidity());
            } catch (IOException ioe) {
                throw new MailboxException("Failed to save Mailbox " + mailbox, ioe);

            }
            folder.setACL(session, mailbox.getACL());
        }
        
    }

    /**
     * @see org.apache.james.mailbox.store.mail.MailboxMapper#list()
     */
    @Override
    public List<Mailbox<Integer>> list() throws MailboxException {
        
       File maildirRoot = maildirStore.getMaildirRoot();
       List<Mailbox<Integer>> mailboxList = new ArrayList<Mailbox<Integer>>();
        
       if (maildirStore.getMaildirLocation().endsWith("/" + MaildirStore.PATH_FULLUSER)) {
           File[] users = maildirRoot.listFiles();
           visitUsersForMailboxList(null, users, mailboxList);
           return mailboxList;
       }
       
       File[] domains = maildirRoot.listFiles();
       for (File domain: domains) {
           File[] users = domain.listFiles();
           visitUsersForMailboxList(domain, users, mailboxList);
       }
       return mailboxList;
        
    }

    /**
     * @see org.apache.james.mailbox.store.transaction.TransactionalMapper#endRequest()
     */
    @Override
    public void endRequest() {
        mailboxCache.clear();
    }
    
    /**
     * Stores a copy of a mailbox in a cache valid for one request. This is to enable
     * referring to renamed mailboxes via id.
     * @param mailbox The mailbox to cache
     * @return The id of the cached mailbox
     */
    private Mailbox<Integer> cacheMailbox(Mailbox<Integer> mailbox) {
        mailboxCache.add(new SimpleMailbox<Integer>(mailbox));
        int id = mailboxCache.size() - 1;
        ((SimpleMailbox<Integer>) mailbox).setMailboxId(id);
        return mailbox;
    }
    
    /**
     * Retrieves a mailbox from the cache
     * @param mailboxId The id of the mailbox to retrieve
     * @return The mailbox
     * @throws MailboxNotFoundException If the mailboxId is not in the cache
     */
    private Mailbox<Integer> getCachedMailbox(Integer mailboxId) throws MailboxNotFoundException {
        if (mailboxId == null)
            throw new MailboxNotFoundException("null");
        try {
            return mailboxCache.get(mailboxId);
        } catch (IndexOutOfBoundsException e) {
            throw new MailboxNotFoundException(String.valueOf(mailboxId));
        }
    }
    
    private void visitUsersForMailboxList(File domain, File[] users, List<Mailbox<Integer>> mailboxList) throws MailboxException {
        
        String userName = null;
        
        for (File user: users) {
            
            
            if (domain == null) {
                userName = user.getName();
            }
            else {
                userName = user.getName() + "@" + domain.getName();
            }
            
            // Special case for INBOX: Let's use the user's folder.
            MailboxPath inboxMailboxPath = new MailboxPath(session.getPersonalSpace(), userName, MailboxConstants.INBOX);
            mailboxList.add(maildirStore.loadMailbox(session, inboxMailboxPath));
            
            // List all INBOX sub folders.
            
            File[] mailboxes = user.listFiles(new FileFilter() {
                @Override
                public boolean accept(File pathname) {
                    return pathname.getName().startsWith(".");
                }
            });
            
            for (File mailbox: mailboxes) {
               
                
                MailboxPath mailboxPath = new MailboxPath(MailboxConstants.USER_NAMESPACE, 
                        userName, 
                        mailbox.getName().substring(1));
                mailboxList.add(maildirStore.loadMailbox(session, mailboxPath));

            }

        }
        
    }

    @Override
    public void updateACL(Mailbox<Integer> mailbox, MailboxACL.MailboxACLCommand mailboxACLCommand) throws MailboxException {
        MaildirFolder folder = maildirStore.createMaildirFolder(mailbox);
        MailboxACL newACL = mailbox.getACL().apply(mailboxACLCommand);
        folder.setACL(session, newACL);
        mailbox.setACL(newACL);
    }
}
