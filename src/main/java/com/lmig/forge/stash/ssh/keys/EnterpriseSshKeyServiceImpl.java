/*
 * Copyright 2015, Liberty Mutual Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lmig.forge.stash.ssh.keys;


import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.ssh.SshAccessKey;
import com.atlassian.bitbucket.ssh.SshAccessKeyService;
import com.atlassian.bitbucket.ssh.SshKey;
import com.atlassian.bitbucket.ssh.SshKeyService;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.lmig.forge.stash.ssh.ao.EnterpriseKeyRepository;
import com.lmig.forge.stash.ssh.ao.SshKeyEntity;
import com.lmig.forge.stash.ssh.ao.SshKeyEntity.KeyType;
import com.lmig.forge.stash.ssh.config.PluginSettingsService;
import com.lmig.forge.stash.ssh.crypto.SshKeyPairGenerator;
import com.lmig.forge.stash.ssh.notifications.NotificationService;
import com.lmig.forge.stash.ssh.rest.KeyPairResourceModel;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;

public class EnterpriseSshKeyServiceImpl implements EnterpriseSshKeyService {
    final private SshKeyService sshKeyService;
    final private SshAccessKeyService sshAccessKeyService;
    final private EnterpriseKeyRepository enterpriseKeyRepository;
    final private SshKeyPairGenerator sshKeyPairGenerator;
    final private NotificationService notificationService;
    final private UserService userService;
    final private PluginSettingsService pluginSettingsService;


    private static final Logger log = LoggerFactory.getLogger(EnterpriseSshKeyServiceImpl.class);

    public EnterpriseSshKeyServiceImpl(SshKeyService sshKeyService, SshAccessKeyService sshAccessKeyService, EnterpriseKeyRepository enterpriseKeyRepository,
                                       SshKeyPairGenerator sshKeyPairGenerator, NotificationService notificationService, UserService userService, PluginSettingsService pluginSettingsService) {
        this.sshKeyService = sshKeyService;
        this.sshAccessKeyService = sshAccessKeyService;
        this.enterpriseKeyRepository = enterpriseKeyRepository;
        this.sshKeyPairGenerator = sshKeyPairGenerator;
        this.notificationService = notificationService;
        this.userService = userService;
        this.pluginSettingsService = pluginSettingsService;

    }


    /**
     * If allowed user ID or Group exception this will add meta to our own records, and return true
     * returns boolean: attempt was valid and added
     */
    private boolean addAllowedBypass(SshKey key, ApplicationUser stashUser) {
        String bambooUser =  pluginSettingsService.getAuthorizedUser();
        String userGroup = pluginSettingsService.getAuthorizedGroup();
        SshKeyEntity sshKeyEntity = null;
        if( bambooUser != null && bambooUser.equals(stashUser.getName())){
            log.debug("Username matches configured 'bambooUser', adding record");
            sshKeyEntity = enterpriseKeyRepository.saveExternallyGeneratedKeyDetails(key,stashUser,SshKeyEntity.KeyType.BAMBOO);
            log.info("Bamboo Key {} created by an authorized bamboo system ID {} ", key.getId(), stashUser.getSlug());
        }else if( userGroup != null && userService.existsGroup(userGroup) && userService.isUserInGroup(stashUser, userGroup)){
            log.debug("Username matches configured 'authorizedGroup', adding record");
            sshKeyEntity = enterpriseKeyRepository.saveExternallyGeneratedKeyDetails(key,stashUser,SshKeyEntity.KeyType.BYPASS);
            log.info("Bypass Key {} created by an authorized user {} in authorized group", key.getId(), stashUser.getSlug());
        }else{
            log.debug("User not in excused roles, do not allow.");
            return false;
        }
        associateKeyWithResource(sshKeyEntity);
        return true;
    }


    @Override
    /**
     * this method should be idempotent, callable by any ssh lifecycle event related to creation or access grants
     */
    public void interceptSystemKey(SshKey key, ApplicationUser user) {
        log.debug(">>>interceptSystemKey checking for key " + key.getId());
        if ( isPluginManagedKey(key) ){
            log.info("No action required, valid key {} is already known.", key.getId());
        }else if( addAllowedBypass(key,user)) {
            log.info("bypassed key {} was added.",key.getId());
        }else{
            sshKeyService.remove(key.getId());
            log.warn("Invalid or illegal key removed for user {} ({})", user.getId(), user.getSlug());
            // TODO issue custom audit event
        }
        log.debug("<<<interceptSystemKey");
    }

    @Override
    public KeyPairResourceModel generateNewKeyPairFor(ApplicationUser user) {
        //purge old key for this user
        removeExistingUserKeysFor(user);
        //create new one
        String keyComment = "ENTERPRISE USER KEY";
        KeyPairResourceModel result = sshKeyPairGenerator.generateKeyPair(keyComment);
        // must add to our repo before calling stash SSH service since audit
        // listener will otherwise revoke it.
        SshKeyEntity newRecord = enterpriseKeyRepository.createOrUpdateUserKey(user, result.getPublicKey(), keyComment);
        SshKey newKey = sshKeyService.addForUser(user, result.getPublicKey());
        enterpriseKeyRepository.updateRecordWithKeyId(newRecord, newKey);
        log.info("New managed key " + newKey.getId() +" of type USER created user {} ({})", user.getId(), user.getSlug());
        return result;
    }

    private void removeExistingUserKeysFor(ApplicationUser user) {
        List<SshKeyEntity> allKeys = enterpriseKeyRepository.keysForUser(user);
        for(SshKeyEntity key: allKeys){
            if(key.getKeyType() == KeyType.USER){
                //this call fires an event that #forgetDeletedKey() will handle
                sshKeyService.remove(key.getKeyId());
            }
        }
    }


    @Override
    public void replaceExpiredKeysAndNotifyUsers() {
        DateTime dateTime = new DateTime();
        Date oldestAllowed = dateTime.minusDays(pluginSettingsService.getDaysAllowedForUserKeys()).toDate();
        //Date oldestAllowed = dateTime.minusMillis(100).toDate(); //for live demos
        List<SshKeyEntity> expiredStashKeys = enterpriseKeyRepository.listOfExpiredKeys( oldestAllowed, KeyType.USER);
        for (SshKeyEntity keyRecord : expiredStashKeys) {
            try{
                ApplicationUser user = userService.getUserById(keyRecord.getUserId());
                String username = ( null != user ? user.getSlug() : "UNKNOWN_ID:"+keyRecord.getUserId());
                log.info("Removing Key for user: {}. KeyId: {}" , username, keyRecord.getKeyId());
                sshKeyService.remove(keyRecord.getKeyId());
                enterpriseKeyRepository.removeRecord(keyRecord);
                notificationService.notifyUserOfExpiredKey(keyRecord.getUserId());
                log.info("Key Removed");
            }catch(Exception e){
                log.error("Key removal failed for user: " + keyRecord.getUserId());
                e.printStackTrace();
            }
        }
    }

    @Override
    public void forgetDeletedKey(SshKey key) {
        try{
            enterpriseKeyRepository.forgetRecordMatching(key);
        }catch(Exception e){
            log.error("Could not remove meta for key: " + key.getId() + ", was likely not tracked by SSH Key Enforcer");
        }
        
    }

    @Override
    public List<SshKeyEntity> getKeysForUser(String username) {
        ApplicationUser user = userService.getUserByName(username);
        return enterpriseKeyRepository.keysForUser(user);
    }

    @Override
    public void associateKeyWithResource(SshKeyEntity key){
        Page<SshAccessKey> keys = sshAccessKeyService.findByKeyForRepositories(key.getKeyId(), new PageRequestImpl(0, 1));
        if( null != keys && keys.getSize() > 0 ){
            log.info("New SSH Key {} is an access key",key.getKeyId());
            SshAccessKey repoKey = keys.getValues().iterator().next();
            if(repoKey.getResource() instanceof Repository){
                key.setRepoId(((Repository)repoKey.getResource()).getId());
            }else if(repoKey.getResource() instanceof Project){
                key.setProjectId(((Project)repoKey.getResource()).getId());
            }
            key.save();
            log.info("Key updated.");
        }
        enterpriseKeyRepository.updateKey(key);
    }

    /*
    * This checks if the plugin was the creator of key type USER, in which case we camn ingore as we have all data
     */
   private boolean isPluginManagedKey(SshKey inspectedKey) {
       SshKeyEntity knownKey = enterpriseKeyRepository.findKeyByText(inspectedKey.getText());
       return null != knownKey;
   }

}