# Guide d'utilisation du module ebx-connectors

Ce guide explique comment configurer et exploiter le module, avec un accent sur les **mappings**. Il est
écrit pour être lu par une personne comme par un agent d'IA : chaque notion est définie une fois, chaque
mécanisme est illustré par un exemple exécutable, et la fin du guide donne les recettes et les appels
exacts pour créer une configuration sans passer par l'interface. English version: `GUIDE.md`.

Sommaire : 1 Vocabulaire · 2 Où vit la configuration · 3 Connecteurs · 4 Mappings · 5 Entrées et
expressions · 6 Règles et chemins · 7 Transformations · 8 Sous-objets · 9 Flux d'import · 10 Sens sortant
et mapping de réponse · 11 Concepteur graphique · 12 Exécuter (service, REST, workflow, planificateur,
déclencheur, webhook) · 13 Journal et statuts · 14 Recettes complètes · 15 Fiche pour un agent d'IA ·
16 Dépannage · 17 Catalogue des connecteurs.

---

## 1. Vocabulaire

| Terme | Définition |
|---|---|
| **Connecteur** | Une application ou un service externe *configuré* : une ligne de la table `Connector` (identifiant, type, URL, authentification, propriétés). Exemples : `GLEIF`, `Melissa`, `Wikidata`. Les écrans l'appellent une **connexion** (menu *Catalogue des connexions*, *Tester la connexion*) et gardent *type de connecteur* pour le code Java qui la porte. |
| **Type de connecteur** | Le code Java qui sait parler à une famille de services : `gleif`, `salesforce`, `jdbc`, `rest`… Un type offre des **opérations**. Plusieurs connecteurs peuvent partager un type (deux bases JDBC). |
| **Opération** | Ce qu'un connecteur sait faire, avec ses **entrées**. Trois genres : **lecture** (rapporter un document pour un enregistrement, ex. GLEIF `lei`), **recherche** (rapporter une liste, ex. GLEIF `searchByName`, SAP `query`), **écriture** (envoyer un document, ex. Salesforce `upsert`, Kafka `publish`). |
| **Document** | Le JSON échangé avec le service : ce qu'il renvoie (sens entrant) ou ce qu'on lui envoie (sens sortant). |
| **Mapping** | La traduction entre un document et une table EBX : un connecteur, une opération, un sens, une table cible, des entrées, des règles. Une ligne de la table `Mapping`. |
| **Sens** | **Entrant** (`INBOUND`) : le document renvoyé par l'opération est écrit dans l'enregistrement EBX. **Sortant** (`OUTBOUND`) : un document est construit depuis l'enregistrement et envoyé. |
| **Entrée** (`MappingInput`) | Un paramètre de l'opération, calculé depuis l'enregistrement par une expression : `siren = ${./LegalInformation/registrationNumber}`. |
| **Règle** (`MappingRule`) | Un lien entre un chemin du document et un champ EBX, avec un pipeline de transformations et une politique d'écriture. |
| **Transformation** | Une fonction appliquée à une valeur : `trim`, `upper`, `left(80)`, `map(A=B)`, `fk(/root/T)`… Enchaînables avec `|`. |
| **Sous-objet** | Un mapping enfant qui traduit *un élément d'une collection* du document vers *une ligne d'une table fille* (adresses, contacts, identifiants). |
| **Flux d'import** | Un mapping entrant dont l'opération renvoie une **liste** (Kafka, requête SQL, fichier, webhook) : chaque élément crée ou met à jour un enregistrement. |
| **Mapping de réponse** | Le mapping entrant jumeau d'un mapping sortant : il stocke dans EBX ce que le service a répondu à l'envoi (un identifiant, un statut). |

**Langue des écrans.** Les écrans sont localisés en anglais et en français (`Messages_en.properties`,
`Messages_fr.properties`, dans la langue de la session EBX de l'utilisateur) et suivent un seul vocabulaire :
**connexion** pour l'objet configuré, **mapping** pour sa traduction entre un document et une table, **flux** pour un
mapping affiché sur la carte d'une connexion, **flux d'import** pour un mapping entrant qui renvoie une liste. Localisés :
libellés, tuiles et statuts ; la cause et l'action suivante des échecs fréquents (§ 12.1) ; les phrases courantes que le
moteur stocke dans le journal de synchronisation (créé, mis à jour, inchangé, entrée vide, aucune donnée, lot de N, import
arrêté…, environ 25), qu'un écran écrit dans la langue du lecteur alors que le texte stocké reste en anglais (c'est ce que
lisent les recherches, les agents, le rejeu et les réponses REST et MCP ; la phrase stockée est dans l'infobulle de la ligne de l'écran) ; les résultats du
diagnostic (*Valider*, *Tester la connexion*, *Aperçu*, *Connecter*). Reste en anglais : le texte stocké dans `SyncLog` et
renvoyé par REST et MCP, ce qu'une connexion répond elle-même (un message de test du système distant, les anomalies de validation des descripteurs listées par *Valider*),
le détail technique replié sous une erreur, le journal du noyau, les libellés de schéma des tables du dataset Connectors
(*Connector*, *Connectors*, *Connector properties*) et la documentation REST et MCP.

## 2. Où vit la configuration

Tout est **de la donnée EBX**, dans le dataspace `Connectors`, jeu de données `Connectors`, créé et
amorcé par le module à son démarrage :

| Table | Contenu |
|---|---|
| `ConnectorCategory` | valeurs de référence regroupant les connecteurs dans le catalogue ; les catégories standard manquantes sont ajoutées au démarrage sans remplacer les lignes définies par le client |
| `Connector` | les connecteurs configurés (40 amorcés, secrets `TO_BE_SET` à saisir) |
| `ConnectorProperty` | propriétés `clé = valeur` d'un connecteur (options, et déclaration des opérations du type `rest`) |
| `Mapping` | les mappings, avec leurs entrées, règles et enfants en associations |
| `MappingInput` | les entrées d'un mapping |
| `MappingRule` | les règles d'un mapping |
| `SyncLog` | le journal : un appel = une ligne (statut, message, durée, origine) ; un flux exécuté par lots écrit une ligne par lot, plus une ligne propre pour chaque enregistrement en échec ou en conflit |
| `Favorite` | les connecteurs que chaque utilisateur a épinglés avec l'étoile de l'explorateur de connecteurs (chacun ne voit que ses lignes, jamais modifiées à la main) |

Accès : **Data > Connectors**. Sur une ligne de `Mapping`, le formulaire dédié propose le bouton
**Concevoir le mapping graphiquement**. Le service **Synchroniser avec une application externe** est
offert sur la vue tabulaire de toute table qui a au moins un mapping. L'API REST du module et Data
Services lisent et écrivent les mêmes tables (§ 15).

### 2.1 Amorçage et modèle du client

Les connecteurs sont toujours amorcés. Les **mappings de démonstration** (sur `/root/Companies`) ne le
sont que s'ils ont un sens dans l'environnement : propriété système `ebx.connectors.seedMappings`
(`-D` sur la JVM de Tomcat) :

| Valeur | Effet |
|---|---|
| `auto` (défaut) | un mapping n'est créé que si sa table existe dans un dataset du nom indiqué, et seulement avec les règles dont le champ EBX existe ; un mapping dont une entrée référence un champ absent, un sous-objet dont la table fille, le champ de lien ou les clés manquent, sont ignorés (une ligne `[connectors] seed:` dans le journal pour chaque élément écarté) |
| `always` | tout est amorcé tel quel, comme avant |
| `never` | connecteurs seulement |

Ensuite, trois **contraintes de validation** (avertissements, jamais bloquantes) surveillent la
configuration en permanence, dans le rapport de validation du dataset Connectors et sur les
formulaires : `Mapping/tablePath` (dataset et table introuvables, champ de lien ou clés d'un sous-objet
absents), `MappingRule/sourcePath` et `targetPath` (le côté EBX de la règle n'est pas un champ de la
table), `MappingInput/expression` (un `${./Groupe/champ}` n'existe pas dans la table).

## 3. Connecteurs

### 3.1 Créer ou configurer un connecteur

Champs de la table `Connector` :

| Champ | Rôle | Exemple |
|---|---|---|
| `id` | identifiant, référencé partout (mappings, workflow, REST) | `GLEIF`, `Melissa`, `MaBase` |
| `category` | clé étrangère vers `ConnectorCategory`, utilisée pour regrouper le connecteur dans le catalogue ; déduite uniquement si elle est vide | `CORPORATE_REGISTRIES` |
| `type` | liste des types enregistrés dans le module (§ 17) | `gleif`, `rest`, `jdbc` |
| `logo` | image affichée avec le connecteur (formulaires, table, concepteur) : une ressource du module `ebx-connectors:ext-images:connect/logos/<fichier>` ; le module livre une vignette générée par connecteur amorcé sous `www/common/images/connect/logos` (`tools/gen_logos.py`), remplacez un fichier par le logo officiel du même nom, ou ajoutez votre SVG ou PNG | `ebx-connectors:ext-images:connect/logos/Salesforce.svg` |
| `logoUrl` | calculé : l'URL de l'image du logo, une chaîne simple ; à utiliser comme **champ image d'une vue en tuiles** (un champ ressource n'y est pas accepté) | `/ebx-connectors/www/common/images/connect/logos/Salesforce.svg` |
| `baseUrl` | racine des appels (ou URL JDBC, `sftp://hôte`) | `https://api.gleif.org/api/v1` |
| `authType` | `NONE`, `BASIC` (`username`/`password`), `API_KEY_HEADER` (`apiKeyHeader` + `apiKey`), `BEARER` (`apiKey`), `OAUTH2` (point de terminaison et flux dans les propriétés `oauth.*`, § 3.1.1) | Melissa : `API_KEY_HEADER` |
| `username`, `password`, `apiKey`, `apiKeyHeader` | identifiants ; `password` et `apiKey` sont chiffrés | |
| `trust` | niveau de confiance 0-100 utilisé par la politique `TRUSTED` (§ 4.4) ; amorcé par type, une saisie manuelle vaut 100 | GLEIF 90, Wikidata 40 |
| `connectTimeoutMs`, `readTimeoutMs`, `active` | délais et activation | |
| propriétés (`ConnectorProperty`) | options propres au type, documentées dans la description du connecteur amorcé | Melissa : `country = FR`, `verified.only = true` |

Un secret laissé à `TO_BE_SET` (ou vide) est reconnu comme **non renseigné** : le
connecteur refuse d'appeler et l'explique (« set the API key in apiKey »). Les secrets se saisissent
dans l'interface EBX : les deux champs déclarent le chiffrement réversible d'EBX
(`PasswordTypeEncryption`, clé du dépôt) et le module les déchiffre au chargement du connecteur.
L'éditeur `osd:password` par défaut ne stockerait qu'un hachage SHA-512, inutilisable par un
connecteur. Une valeur qui ne se déchiffre pas est utilisée telle quelle : une clé écrite en clair
par Data Services ou un import fonctionne aussi, mais reste alors stockée en clair. Un changement de
secret, d'URL ou de propriété est pris en compte à l'appel suivant, sans redémarrage.

**Guide des identifiants.** L'onglet *Connexion et configuration* d'un connecteur indique quels
champs d'identifiants remplir pour le mode d'authentification choisi. Les modes proposés par le type
de connecteur apparaissent en cartes, les autres sont grisés. Chaque ligne porte un état
(obligatoire, optionnel, déduit, non utilisé) et un libellé contextuel : « Clé API / jeton » devient
« Client secret » en OAuth 2.0, « Jeton » en Bearer, « Chaîne de connexion » pour Azure Service Bus.
Les panneaux latéraux disent quoi saisir, où l'obtenir chez le fournisseur et ce que le module envoie
réellement. Un compteur avertit quand un secret collé dépasse les 255 caractères du champ, typiquement
un jeton Entra de courte durée collé en mode Bearer au lieu d'un service principal OAuth 2.0. Les
règles sont dans `core/CredentialGuide` (raffinements par type compris) et les textes dans les clés
`credentials.*` des bundles `Messages`. Le guide ne modifie aucune valeur : les widgets natifs d'EBX
gardent la validation, les permissions et le chiffrement.

### 3.1.1 Secrets, coffres-forts, OAuth 2.0 et TLS mutuel

**Références de secret.** Tout champ secret ou propriété peut contenir une référence plutôt que la
valeur, pour que le secret vive hors du dépôt EBX : `env:SIRENE_KEY` (variable d'environnement),
`file:/run/secrets/sirene` (secrets Docker et Kubernetes),
`vault:secret/data/ebx/sirene#apiKey` (HashiCorp Vault KV v1 ou v2 ; adresse
`-Debx.connectors.vault.address` ou `VAULT_ADDR`, jeton `-Debx.connectors.vault.token`, `VAULT_TOKEN`
ou `~/.vault-token`, espace de noms `VAULT_NAMESPACE`), `azurekv:monvault/sirene-key` (Azure Key
Vault ; principal de service `AZURE_TENANT_ID` / `AZURE_CLIENT_ID` / `AZURE_CLIENT_SECRET`, ou identité
managée de l'hôte), ou `awssecrets:eu-west-3/ebx/sirene#apiKey` (AWS Secrets Manager
`GetSecretValue` ; `versionStage` ou `versionId` facultatif ; identifiants statiques/temporaires,
profil partagé borné, rôle de tâche ECS, puis EC2 IMDSv2—jamais IMDSv1). Les appels ECS et IMDS sont
bornés, refusent les redirections et restent soumis à la liste d'hôtes autorisés de la JVM ; ou
`gcpsecret:projects/mon-projet/secrets/sirene-key` (Google Secret Manager ; le suffixe facultatif
`/versions/<version>` vaut `latest` par défaut ; `GOOGLE_OAUTH_ACCESS_TOKEN` explicite, document de
compte de service borné via `GOOGLE_APPLICATION_CREDENTIALS`, puis identité de métadonnées Google).
Les réponses Google sont bornées, les redirections sont refusées et le CRC32C de la charge utile est
vérifié lorsqu'il est fourni. Les documents d'identité externe/workload identity acceptent les jetons
sujets texte ou JSON provenant d'un fichier local borné, l'échange STS RFC 8693 et l'usurpation
facultative d'un compte de service. Les sources URL, AWS et exécutables sont refusées. Une référence
saisie dans l'interface est chiffrée comme un secret, puis résolue au
chargement du connecteur ; les valeurs résolues sont gardées `ebx.connectors.secrets.cacheSeconds`
(300) : un secret tourné dans le coffre est pris en compte sans redémarrage. Une référence qui ne se
résout pas rend le connecteur inutilisable, avec la raison dans l'erreur (« Vault address not set »,
« key 'x' not found... »). D'autres coffres (CyberArk...) s'ajoutent par la SPI
`SecretResolver` (§ 3.3) : une classe, un schéma, déclarés dans
`META-INF/services/com.on.ebx.connect.spi.SecretResolver`. Avec
un secret ni chiffré par l'interface ni référence est refusé, sauf `-Debx.connectors.secrets.allowClear=true` (depuis le 2026-10-06).

`env:` et `file:` lisent la machine où tourne EBX, pour le compte de quiconque peut modifier un
connecteur, et la valeur part ensuite vers l'hôte que ce connecteur désigne. Leur portée se règle
donc au niveau de la JVM :

| Propriété | Effet | Défaut |
|---|---|---|
| `ebx.connectors.secrets.dir` | le répertoire des fichiers de secrets (`/run/secrets`) : une référence `file:` n'est lue que si le fichier s'y trouve, `..` et liens symboliques résolus ; une référence relative (`file:sirene`) désigne un fichier de ce répertoire ; tout le reste, fichier absent compris, répond « no such file in the secrets directory ». Un répertoire qui n'existe pas refuse toute référence `file:`. **Réglage de production** | absent : tout fichier lisible par le processus EBX, `ebx.properties` compris (un avertissement dans le journal kernel) |
| `ebx.connectors.secrets.envAllowed` | ce que `env:` peut lire, par noms ou préfixes : `SIRENE_KEY, EBX_SECRET_*` (un `*` seul ne correspond à rien). Un nom listé est cherché comme propriété système (`-DSIRENE_KEY=...`), puis comme variable d'environnement. **Réglage de production** | absent : toute variable d'environnement, et aucune propriété système (un avertissement dans le journal kernel) |

Un fichier de secret fait 1 Mio au plus. Les propriétés système sont hors de portée d'`env:` tant
qu'elles ne sont pas listées, parce qu'elles portent les secrets de la JVM elle-même (le jeton Vault
de ce module, les mots de passe de magasins de clés).

**OAuth 2.0** (`authType = OAUTH2`, pour `rest`, `graphql`, `soap` et tout type fondé sur REST) : le
point de terminaison est `oauth.tokenUrl`, le flux `oauth.flow` = `client_credentials` (défaut),
`password` (identifiant / mot de passe du connecteur), `refresh_token` (`oauth.refreshToken`) ou
`jwt_bearer` (`oauth.privateKey` = clé RSA PKCS#8 en PEM, en général une référence `vault:` ou `file:` ;
`oauth.issuer`, `oauth.subject`, `oauth.jwtAudience`, `oauth.jwtLifetimeSeconds`, revendications
supplémentaires `oauth.claim.<nom>`). L'identifiant client est `oauth.clientId` (ou `clientId`), le
secret client le champ **Clé API / jeton** (ou `oauth.clientSecret`), envoyé dans le corps ou, avec
`oauth.clientAuth = basic`, en en-tête Basic ; `oauth.scope`, `oauth.audience`, `oauth.resource` et
`oauth.extra.<nom>` complètent la requête. Les jetons sont gardés par connecteur et renouvelés une
minute avant expiration ; un 401 jette le jeton et l'appel est rejoué une fois. Salesforce garde sa
propre connexion (client credentials) et accepte `oauth.flow = jwt_bearer` avec un certificat. Son flux
`password` marche encore mais est **obsolète** : Salesforce retire le flux OAuth 2.0 username-password le
20 février 2027, et ces connexions échoueront ensuite. Les diagnostics du connecteur et `connectors_describe`
affichent un avertissement pour une telle connexion ; passez-la en `client_credentials` (ou `jwt_bearer`) avant
cette date.

**TLS mutuel et autorités privées** : `tls.keystore` (PKCS#12 ou JKS avec le certificat client),
`tls.keystorePassword`, `tls.keyPassword`, `tls.truststore` (une AC privée), `tls.truststorePassword` ;
les mots de passe peuvent être des références. Sans ces propriétés, la JVM fait foi (truststore, proxy) ;
il n'y a volontairement pas d'option « tout accepter ». Kafka prend ses options TLS par les propriétés
`kafka.ssl.*`.

**Clé d'hôte SFTP** : un connecteur `sftp` vérifie la clé du serveur d'après la propriété
`knownHosts` (chemin d'un fichier known_hosts sur le serveur EBX) ou `hostKey` (la clé publique
elle-même, telle que `ssh-keyscan <hôte>` l'affiche ; plusieurs clés possibles). Sans l'une ni
l'autre, il refuse de se connecter et dit quelle propriété poser : un serveur non vérifié peut être
n'importe qui sur le chemin, et il reçoit le mot de passe. `strictHostKey = false` connecte sans
vérification, avec un avertissement dans le journal kernel ; c'est prévu pour un serveur de test.

**Webhooks** : l'appelant s'authentifie comme un utilisateur EBX (Basic ou jeton) qui détient le droit RUN ; l'import
s'exécute sous ce compte technique. Les modes « secret partagé » (`X-Webhook-Secret`) et « signature »
(`X-Webhook-Signature`) n'existent plus, et la connexion de type webhook ne stocke ni secret ni compte.

**Connexions JDBC** : une ligne de connecteur est une donnée, mais une URL JDBC, une classe de driver, un nom
JNDI et les propriétés `jdbc.*` sont ce que le serveur charge ou exécute. Par défaut un nom JNDI doit
commencer par `java:comp/env/`, la classe de driver doit être celle du sous-protocole de l'URL, les options
qui chargent une classe, exécutent du SQL ou lisent/écrivent un fichier sont refusées (H2 `INIT`,
`socketFactory`, `sslfactory`, `loggerFile`, MySQL `allowLoadLocalInfile`, `autoDeserialize`...), une URL H2
doit être `tcp:`, `ssl:` ou `mem:`, et un H2 en mémoire refuse `CREATE ALIAS` et les fonctions de fichier.
Chaque refus nomme la propriété `ebx.connectors.jdbc.*` du tableau ci-dessus qui l'autorise.

**Qui peut modifier les connecteurs** : une `baseUrl` envoie des données EBX là où elle pointe.
Réserver le jeu de données Connectors à un rôle d'administration, et garder l'historique de la table
Connector actif.

### 3.1.2 Durcissement : liste blanche sortante, masquage, rétention, rôle d'administration

Des propriétés JVM, posées par l'infrastructure et donc hors de portée de l'interface EBX :

| Propriété | Effet | Défaut |
|---|---|---|
| `ebx.connectors.allowedHosts` | les hôtes que le module peut appeler : `api.insee.fr, *.api.gouv.fr, kafka.internal:9082, localhost` ; `none` refuse tout. Chaque appel HTTP (connecteurs, points de terminaison de jeton, coffre) est contrôlé, ainsi que les URL de base des connecteurs JDBC, SFTP et Kafka à leur chargement : un connecteur qui pointe ailleurs est refusé avec un message explicite. `file:/etc/ebx/egress.txt` lit les motifs dans un fichier (un par ligne, commentaires `#`) relu quand il change : l'exploitation ajoute un hôte sans redémarrage, et les droits du fichier décident qui le peut. Un fichier absent ou illisible refuse tout. Une adresse IPv6 s'écrit `[2001:db8::1]` ou `[2001:db8::1]:8443` | absent : aucune restriction (un avertissement dans le journal kernel) |
| `ebx.connectors.egress.requireAllowList` | `true` : tant que `ebx.connectors.allowedHosts` n'est pas posée, tout appel sortant est refusé avec un message qui nomme les deux propriétés, au lieu que tout soit permis. **Réglage de production** | absent : une liste absente autorise tout |
| `ebx.connectors.egress.blockPrivate` | **actif par défaut** (`false` le désactive) : un hôte qui est, ou dont le nom se résout en, une adresse de bouclage ou privée (127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, `::1`, `fc00::/7`), ou qui ne se résout pas, est refusé, sauts de redirection compris, sauf si `ebx.connectors.egress.privateExceptions` le liste (`localhost:9092`, `192.168.1.5`, `10.1.0.0/16` ou `file:<chemin>` ; elle n'active pas la liste d'hôtes) ou si une entrée **exacte** de la liste d'hôtes le nomme | actif |
| `ebx.connectors.egress.privateExceptions` | les cibles privées qui restent permises : pour un serveur de démo local `localhost:9092,localhost:9095,localhost:9082,localhost:8080,localhost:11434` (et les formes `127.0.0.1:` si les connexions utilisent l'adresse) | absent |
| `ebx.connectors.role.designer` (ancien nom : `ebx.connectors.adminRole`) | le nom du rôle concepteur (MANAGE). Depuis l'audit C-01 (tables), les droits s'appliquent à toutes les tables du dataset Connectors, que la propriété soit posée ou non : seul MANAGE (concepteurs, administrateurs) modifie les tables de configuration, RUN et READ les voient en lecture seule, une session sans droit ne voit pas les lignes (docs/OPERATIONS.md section 4, « Rights on the tables ») | `ConnectorsDesigner` |
| `ebx.connectors.log.retentionDays` | **Activée par défaut, 90 jours.** Les lignes du journal, les conflits tranchés, les exécutions terminées (les lignes `ExecutionRun` lues par `connectors_run_get`) et les rapports de publication de plus de N jours sont supprimés par une tâche de fond du module, une fois par heure, 500 lignes par transaction. Un conflit ouvert et une exécution en cours ne sont jamais supprimés ; la provenance des champs et les états sortants ne sont purgés que sur demande (`.outboundStateDays`, `.provenanceDays` : seulement les états des enregistrements supprimés et la provenance des enregistrements qui n'existent plus). Une exécution dont les lignes sont purgées ne peut plus être rejouée ni reprise. Chaque table peut avoir sa propre durée, ou `0` pour être gardée indéfiniment : `ebx.connectors.retention.syncLogDays`, `.conflictDays`, `.executionRunDays`, `.publicationDays` | 90 (`0` : rien n'est jamais supprimé) |
| `ebx.connectors.log.storeChanges` | `false` garde les valeurs et les documents sortants hors du journal (données personnelles) ; message et statut restent | `true` |
| `ebx.connectors.nodeId` | nom de ce nœud EBX dans le bail d'un import en cours ; donnez-en un à chaque JVM qui partage un dépôt (deux JVM sur une même machine comprises). Un nœud qui démarre interrompt les exécutions laissées RUNNING par sa vie précédente et celles dont le bail est échu, jamais une exécution qu'un autre nœud détient encore | le nom de la machine |
| `ebx.connectors.run.leaseSeconds` | durée du bail d'un import en cours après chaque lot (60 à 3600) | `1800` |
| `ebx.connectors.secrets.hideProperties` | `false` montre la `value` d'une propriété d'identification (`oauth.clientSecret`, `oauth.refreshToken`, `oauth.privateKey`, une clé contenant `password`, `token`, `secret`...) à toute session qui peut lire la table ; par défaut seuls les concepteurs, les administrateurs et l'utilisateur système la voient, dans l'interface, Data Services et le MCP `data-access`. Les champs `password` et `apiKey` du connecteur sont, eux, stockés chiffrés | `true` |
| `ebx.connectors.jdbc.jndiPrefixes` | préfixes JNDI qu'un connecteur JDBC peut consulter en plus de `java:comp/env/` (`java:/`...). Un autre nom est refusé | `java:comp/env/` seulement |
| `ebx.connectors.jdbc.allowedDrivers` | classes de driver autorisées en plus de la table intégrée : un driver doit servir le sous-protocole de l'URL (`org.postgresql.Driver` pour `jdbc:postgresql:`...) | la table intégrée |
| `ebx.connectors.jdbc.allowedOptions` | noms d'options exemptés du refus des options d'URL et des propriétés `jdbc.*` qui chargent une classe, exécutent du SQL ou touchent un fichier (H2 `INIT`, `socketFactory`, `sslfactory`, `loggerFile`, MySQL `allowLoadLocalInfile`, `autoDeserialize`, `queryInterceptors`...) | aucune exemption |
| `ebx.connectors.jdbc.allowH2File` | `true` autorise les bases fichier (`jdbc:h2:file:`, `~/x`, un nom nu) ; `tcp:`, `ssl:` et `mem:` sont toujours autorisés | `false` |
| `ebx.connectors.jdbc.allowH2Code` | `true` autorise `CREATE ALIAS`, `CREATE TRIGGER`, `FILE_READ`, `CSVREAD`... sur un H2 en mémoire, qui vit dans la JVM d'EBX | `false` |

Deux règles sortantes s'appliquent quels que soient les réglages. **Les adresses link-local et de
métadonnées cloud** (169.254.0.0/16, fe80::/10, `fd00:ec2::254`, `metadata.google.internal`, les
adresses de métadonnées d'Alibaba et d'Oracle) sont refusées, par adresse littérale comme par les
adresses qu'un nom résout, sauf si une entrée exacte de la liste nomme l'hôte : elles délivrent les
identifiants de la machine. (Un appel qui sort par le proxy HTTP de la JVM est jugé sur l'hôte écrit
dans son URL : c'est le proxy qui résout le nom.) Les recherches d'identité de charge propres au module (`awssecrets:`,
`gcpsecret:`, `azurekv:` avec l'identité de l'hôte) ne sont pas concernées, mais restent soumises à
la liste quand il y en a une. **Les redirections** ne sont jamais suivies à l'aveugle : 5 sauts au
plus, chacun contrôlé comme un premier appel (liste et règles d'adresse) ; dès qu'un saut quitte
l'hôte ou le port appelé, seuls `Accept`, `Accept-Language` et les en-têtes `Content-*` suivent, si
bien qu'`Authorization`, les en-têtes de clé d'API, les cookies et les signatures restent chez le
serveur auquel ils étaient destinés ; une redirection vers un autre schéma (https vers http ou
l'inverse) est rendue telle quelle. Les mêmes règles couvrent le téléchargement d'une spécification
OpenAPI par URL (http ou https uniquement, 16 Mio au plus). En production : poser
`ebx.connectors.allowedHosts`, `ebx.connectors.egress.requireAllowList=true` et, quand cela convient,
`ebx.connectors.egress.privateExceptions` pour les hôtes privés réellement appelés (le blocage des hôtes privés est actif par défaut).

Quels que soient les réglages, les textes que le module conserve ou renvoie (journal, réponses REST,
sorties de workflow, log du noyau) sont **masqués** : les secrets des connecteurs chargés, les
paramètres secrets des URL (`key`, `api_key`, `token`, `signature`...) et les en-têtes `Authorization`
recopiés sont remplacés par `***`. Tant que `ebx.connectors.secrets.allowClear` n'est pas `true` (§ 3.1.1, valeur par défaut) le dépôt ne
contient jamais de secret en clair. Avec les références de coffre, OAuth 2.0 et le TLS mutuel du
§ 3.1.1, c'est le socle qu'un architecte attend avant une mise en production ; le reste appartient au
client : historique de la table Connector, rétention de l'audit EBX, et gouvernance de ce que les
connecteurs LLM ont le droit de recevoir. Cela inclut les images et les scans quand un connecteur
`documents` a `vision = true` (§ 14.8) : photos, signatures et pièces d'identité parviennent alors au
fournisseur du modèle.

### 3.1.3 Qui peut faire quoi : les trois droits Connectors

Rien n'est ouvert par défaut. Un appelant qui n'est ni administrateur EBX ni membre d'un rôle portant l'un
des trois droits ci-dessous se voit refuser tous les outils MCP (sur `/ebx-connectors/rest/mcp`, sur le serveur MCP natif
comme sur le pont en JVM), toutes les routes REST de `/ebx-connectors/rest/v1` (réponse `403`) et tous
les services d'écran du module (l'action n'est ni affichée ni exécutable). `tools/list` ne liste que les
outils que l'appelant peut appeler.

| Droit | Rôle par défaut | Ce qu'il ouvre |
|---|---|---|
| READ (lecteur) | `ConnectorsViewer` | lister et décrire les connexions (secrets masqués), lister et lire les mappings, lire une exécution et le journal, planifier une source de catalogue ; les écrans Catalogue des connexions et Opérations |
| RUN (opérateur) | `ConnectorsOperator` | READ, plus : `connectors_sync`, `connectors_enrich`, `connectors_replay`, `connectors_run_stop`, `connectors_publish`, `connectors_lineage_declare` ; les documents sortants et les conflits (ils portent des valeurs d'enregistrements) et la décision sur un conflit ; l'action « Synchroniser avec une application externe » et le gestionnaire de conflits |
| MANAGE (concepteur) | `ConnectorsDesigner` | RUN, plus : tout changement de configuration (`connectors_mapping_upsert`, `connectors_mapping_delete`, `connectors_config_import`, `connectors_openapi_import`, modèles, application d'une source de catalogue), `connectors_config_export`, `connectors_call` (n'importe quelle opération d'une connexion, avec ses identifiants stockés), la description des champs d'une opération (elle échantillonne le système distant) ; le concepteur de mapping, les diagnostics, l'import OpenAPI et la suppression d'un mapping |

**Donner les droits : la table « Droits d'accès ».** Un administrateur associe les droits à des rôles
qui existent déjà dans l'annuaire, dans l'interface : perspective *Data connector* → *Administration* →
*Droits d'accès* (table `AccessRight` du jeu de données Connectors). Une ligne = un rôle de l'annuaire
(choisi dans la liste des rôles existants, ou « Tout le monde ») et son droit ; un commentaire dit
pourquoi. Une personne reçoit le droit le plus large que lui apporte l'un de ses rôles. Un changement
s'applique en quelques secondes, sans redémarrage. Seul un administrateur EBX peut modifier cette table :
un concepteur ne peut pas donner de droits.

**La connexion de modèle de langage par défaut : le groupe « Paramètres ».** Les autres modules ON EBX
(MDM Factory, catalogue de données) interrogent un modèle de langage par Connectors ; quand ils ne nomment
pas de connexion, ils reçoivent celle qu'un administrateur a choisie dans *Connecteur de modèle de langage
par défaut*, dans le groupe *Paramètres* du jeu de données Connectors (espace et jeu de données
`Connectors`, panneau de navigation). Seule une connexion de type `llm` est acceptée. Vide : la propriété
JVM `-Debx.connectors.llm.default`, sinon la seule connexion `llm` utilisable s'il n'y en a qu'une. Lu à
chaque appel, sans redémarrage. Seul un administrateur EBX le modifie. Quoi qu'un module demande, une
réponse n'est jamais plus longue que la propriété `maxTokens` de la connexion (1024 si elle n'est pas
renseignée) : l'augmenter sur la connexion quand un module a besoin de réponses plus longues.

Sans aucune ligne, trois noms de rôle fonctionnent d'emblée (`ConnectorsViewer`, `ConnectorsOperator`,
`ConnectorsDesigner`) : les créer dans l'annuaire si l'on préfère des rôles dédiés. Ils se renomment par
`-Debx.connectors.role.viewer`, `-Debx.connectors.role.operator` et `-Debx.connectors.role.designer`
(`-Debx.connectors.adminRole` reste lu comme rôle concepteur). La table et ces rôles s'additionnent. Un
refus est écrit dans le log du noyau (`[connectors] access denied: user ..., tool ...`).

Trois points à connaître :

- **Les workflows et les tâches planifiées ne sont pas concernés.** Une tâche de script de workflow, une
  tâche planifiée et le trigger de table exécutent ce qu'un concepteur a configuré, sous la session
  qu'EBX leur donne ; le point de contrôle est qui peut configurer le mapping et publier le workflow.
- **Un webhook** exige un utilisateur EBX avec le droit RUN ; le secret partagé et la signature ont été retirés.
- **Les droits ouvrent les points d'entrée du module, pas les tables.** Lire ou modifier les lignes du jeu
  de données Connectors par EBX lui-même (interface, data services) reste régi par les permissions EBX de
  ce jeu de données, plus la règle d'accès du § 3.1.2 quand le rôle concepteur est fixé par propriété.

**Un appel qui modifie ne doit pas venir d'un autre site.** Un `POST`, `PUT`, `PATCH` ou `DELETE` sur
`/ebx-connectors/rest/...` (point d'accès MCP compris) n'est accepté qu'avec un en-tête `Authorization`
(ce qu'envoient `curl -u`, un script ou un client MCP), ou un en-tête `X-Requested-With`, ou depuis une
page de cet EBX (l'`Origin` du navigateur désigne le même hôte et le même port). Tout le reste reçoit `403` et une ligne dans le log
du noyau (`[connectors] cross-site request refused: ...`) : une page d'un autre site ne peut pas se
servir de la session d'un utilisateur connecté. Envoyer les identifiants avec la requête elle-même : un
client qui attend un `401` avant de s'authentifier peut recevoir ce `403` à la place.

**Qui a exécuté ou modifié quoi.** Chaque appel d'outil MCP et chaque route REST qui demande RUN ou
MANAGE laisse une ligne dans le log du noyau, qu'il ait réussi, échoué ou été refusé :
`[connectors] audit: user=alice tool=connectors_sync mapping=12 dryRun=true outcome=ok ms=532` (une route
REST s'écrit `rest=sync ... status=200`). La ligne nomme l'utilisateur, l'outil ou la route, la
connexion, le mapping, l'exécution ou le conflit concerné, l'issue et la durée ; elle ne porte jamais de
document, d'entrée, de clé d'enregistrement ni de secret. Les lectures ne sont pas tracées. Les actions
lancées depuis les écrans, les workflows et les tâches planifiées se retrouvent dans le journal de
synchronisation et les exécutions, pas dans cette ligne.

### 3.2 Un connecteur REST sans Java : le type `rest`

Le type `rest` se déclare entièrement par propriétés, une opération = trois propriétés :

```
op.<nom>.method = GET | POST | PUT | DELETE     (GET/DELETE = lecture, POST/PUT = écriture)
op.<nom>.path   = /w/api.php                    ({placeholders} = entrées du mapping, encodés)
op.<nom>.query  = action=wbsearchentities&search={name}&language=fr&format=json&limit=1
```

Exemple réel, le connecteur `Wikidata` (`baseUrl = https://www.wikidata.org`, auth `NONE`) :

```
op.search.method = GET
op.search.path   = /w/api.php
op.search.query  = action=wbsearchentities&search={name}&language=fr&format=json&limit=1&type=item
op.claims.method = GET
op.claims.path   = /w/api.php
op.claims.query  = action=wbgetclaims&entity={id}&format=json
```

Le mapping fournit `name` (ou `id`) en entrée ; le document renvoyé est le JSON brut de l'API.

### 3.3 Un connecteur en Java

Une classe qui implémente `com.on.ebx.connect.spi.Connector` (ou étend `AbstractRestConnector`) :
`getType()`, `getLabel()`, `getOperations()`, `configure(ConnectorConfig)`, `execute(contexte, opération,
entrées, documentSortant)` qui renvoie un `JsonNode` (ou `null` = pas de donnée), et éventuellement
`describeFields(...)` pour alimenter le concepteur. Les types intégrés sont enregistrés dans
`ConnectorRegistry`.

**Sans les sources du module : un jar d'extension.** Le module charge aussi toute implémentation déclarée
par le mécanisme standard `java.util.ServiceLoader` ; un tiers ajoute donc un type en livrant un jar :

1. compiler contre **`dist/ebx-connectors-spi.jar`** (produit par `build-spi.sh` : le paquet `spi` plus les
   classes d'aide `AbstractRestConnector`, `HttpJsonClient` et `Json` ; un jar de sources l'accompagne),
   avec `ebx.jar` (ou `ebx-lib.jar` sous FastTrack) et Jackson dans le classpath, en syntaxe Java 8 (`--release 8`) ;
2. ajouter le fichier `META-INF/services/com.on.ebx.connect.spi.Connector` contenant le nom de la classe
   (une par ligne) ; la classe doit avoir un constructeur public sans argument ;
3. déposer le jar dans `ebx-connectors/WEB-INF/lib/` (les bibliothèques tierces dont le connecteur a
   besoin vont au même endroit, ou dans le `lib` partagé de Tomcat), redémarrer : le type est dans la liste
   `Connector/type`, ses opérations et ses champs dans le concepteur.

Le projet **`ebx-connectors-extension-sample`** (à côté du module) est un exemple complet et compilable :
un géocodeur OpenStreetMap Nominatim (type `nominatim`, opérations `geocode` et `reverse`, une propriété
`user.agent` transformée en en-tête HTTP en surchargeant `headers()`), son `build.sh`, son fichier de
services et ses fichiers Eclipse. Copiez-le pour démarrer votre propre connecteur.

### 3.3.1 Publier un produit entier : le SPI `TablePublisher`

Un connecteur capable de recevoir un **produit de données entier** (§ 10.2) implémente en plus
`com.on.ebx.connect.spi.TablePublisher`, sans dépendance EBX comme le reste du SPI : `nativeType(column)`
(le type natif utilisé pour une colonne, pour les rapports et le DDL), `plan(spec, recreate)` (les
instructions qu'une exécution réelle lancerait, rien n'est exécuté — le chemin de simulation),
`prepare(ctx, spec, recreate)` (schéma, table de contrôle, tables cibles et de préchargement ; renvoie
`CREATED`/`EXISTS`/`RECREATED` par table), `load(ctx, spec, table, documents)` (charge un lot dans la
zone de préchargement d'une table), `commit(ctx, spec, rowsPerTable)` (l'unique étape atomique : chaque
table cible remplacée par le contenu de sa zone de préchargement, en une seule requête), et
`cleanup(ctx, spec)` (supprime les tables de préchargement, au mieux, appelée après le commit et après
tout échec). Les identifiants (parties du schéma, noms de table et de colonne) sont validés et mis en
majuscules par l'appelant (`[A-Z_][A-Z0-9_]*`) avant que le SPI ne les voie ; l'implémentation n'a plus
qu'à les entourer de guillemets doubles. `connectors/SnowflakePublicationSql` (package-privé, sans
dépendance, testé directement) est l'implémentation de référence, utilisée par `SnowflakeConnector
implements TablePublisher` : correspondance des types, `DESCRIBE TABLE`/`CREATE TABLE`/`CREATE OR
REPLACE TABLE`, un chargement par lots via `FLATTEN` dans une table de préchargement `__STG`, et une
seule requête multi-instructions `BEGIN … COMMIT` (paramètre `MULTI_STATEMENT_COUNT` de l'API SQL
Snowflake) pour le commit atomique. `core.ProductPublisher` (§ 10.2) est le seul appelant de ce SPI ;
un connecteur qui ne l'implémente pas ne peut simplement pas recevoir de publication
(`connectors_publish` répond une `ERROR` claire nommant le connecteur et son type).

### 3.4 Créer un connecteur depuis une spécification OpenAPI

Le module lit une spécification **OpenAPI 3** (JSON, ou YAML grâce à `jackson-dataformat-yaml` et
`snakeyaml`, que le WAR embarque depuis la 1.4.1 ; une spécification YAML de plus de 3 Mio est refusée) et en fait un connecteur de type `rest` :

| Dans la spécification | Dans le connecteur |
|---|---|
| `servers[0].url` (variables remplacées par leur défaut) | `baseUrl` ; relative → à compléter à la main, signalé |
| `securitySchemes` : apiKey en en-tête / en query, http basic, http bearer, oauth2 | `authType` + `apiKeyHeader` (ou propriété `auth.apiKeyQuery`) ; le secret reste à saisir |
| chaque `paths.{chemin}.{get\|post\|put\|delete}` | `op.<operationId>.method` / `.path` / `.query` (paramètres de requête en `nom={nom}`) ; sans `operationId`, le nom vient du chemin |
| paramètres de chemin et paramètres `required` | `op.x.required` (liste ; `-` = aucun obligatoire) ; les autres sont facultatifs et non envoyés quand vides |
| `summary` / `description` | `op.x.description`, visible dans la liste des opérations |
| schéma de la réponse 200 (JSON) | `op.x.fields` : `chemin=type` aplatis, tableaux en `nom[0]`, `$ref` résolus, `allOf` fusionnés ; alimente l'arbre d'entrée du concepteur sans appel |
| schéma du corps d'un POST/PUT | `op.x.bodyFields` : les champs proposés côté sortie d'un mapping sortant |

Une opération dont la réponse est une liste (`[0]...` ou `résultats[0]...`) est de genre **recherche**.
Les paramètres d'en-tête ou de cookie sont ignorés et signalés ; `oneOf`/`anyOf` prennent la première
branche ; 300 champs et 6 niveaux au plus.

Deux façons d'importer :

- **Interface** : sur la vue tabulaire de la table `Connector`, action **Importer une spécification
  OpenAPI** : identifiant, libellé, URL *ou* texte collé, remplacer un connecteur existant (ses
  secrets sont conservés), aperçu sans créer.
- **API** : `POST /ebx-connectors/rest/v1/connectors/openapi?id=RechercheEntreprises[&label=][&replace=true][&dryRun=true]`
  avec la spécification en corps, ou `&url=https://recherche-entreprises.api.gouv.fr/openapi.json`.

```bash
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/connectors/openapi?id=RechercheEntreprises&url=https://recherche-entreprises.api.gouv.fr/openapi.json"
```

Réponse : le connecteur créé ou mis à jour, `baseUrl`, `authType`, les opérations avec leurs
obligatoires, le nombre de propriétés, et les notes (ce qui reste à faire à la main). Le connecteur se
retouche ensuite comme tout connecteur `rest` ; réimporter avec `replace` met à jour opérations et
champs sans toucher aux identifiants.

### 3.5 Power BI pour le catalogue de données : le type `powerbi`

`PowerBiConnector` lit un tenant Power BI pour le catalogue de données (`ebx-data-catalog`) :
rapports, tableaux de bord, modèles sémantiques avec leurs tables, colonnes, colonnes calculées et
mesures (le DAX est conservé dans l'`expression` de la colonne), dataflows et leurs entités, et le
lineage entre tout cela et leurs sources de données. Aucune bibliothèque : API REST Power BI v1.0
via `HttpJsonClient`.

**Ligne connecteur** : `baseUrl` = `https://api.powerbi.com/v1.0/myorg`, `authType` = `OAUTH2`,
propriétés `tenantId` et `clientId` (un service principal Microsoft Entra), le secret client dans
`apiKey` ; le point de terminaison de jeton et le scope
`https://analysis.windows.net/powerbi/api/.default` sont déduits. Un jeton utilisateur dans `apiKey`
avec `authType` = `BEARER` marche aussi pour un essai. Si la liste blanche sortante est appliquée
(§3.1.2), autoriser `api.powerbi.com` et `login.microsoftonline.com`.

**Deux modes de scan** (propriété `scanner`, défaut `auto`) :

- `admin` — le Scanner API (`admin/workspaces/getInfo` avec `lineage`, `datasourceDetails`,
  `datasetSchema`, `datasetExpressions`, puis interrogation de `scanStatus` et `scanResult` ;
  définitions des dataflows par `admin/dataflows/{id}/export`). Tout le tenant, avec le schéma des
  tables, le Power Query M et le DAX. Le service principal doit être autorisé par trois réglages du
  tenant : *Allow service principals to use read-only admin APIs*, *Enhance admin APIs responses
  with detailed metadata* et *Enhance admin APIs responses with DAX and mashup expressions*.
- `standard` — `groups`, `reports`, `datasets`, `dashboards` (+ tuiles), `dataflows` des espaces de
  travail dont le principal est membre : objets et liens seulement, ni schéma ni expression.
- `auto` essaie `admin` et retombe sur `standard` sur un 401/403. `testConnection` dit quel mode est
  disponible.

**Opérations** (toutes entrantes ; `source` = identifiant de la source catalogue, préfixe de toutes
les clés d'actifs ; `schemas` = noms ou identifiants d'espaces de travail séparés par des virgules,
vide = tous les espaces non personnels) :

| Opération | Document | Notes |
|---|---|---|
| `catalog` | `{tables:[{assetKey, qualifiedName, schema, name, kind, url, remarks, columns:[{columnKey, name, dataType, nativeType, expression…}]}]}` | natures `REPORT`, `DASHBOARD`, `SEMANTIC_MODEL`, `DATAFLOW`, `TABLE` (tables de modèle et entités de dataflow) ; mesures et colonnes calculées sont des colonnes de `nativeType` `MEASURE` / `CALCULATED …` ; `types` filtre les natures (une valeur « base de données » comme `TABLE,VIEW` prend tout) |
| `lineage` | `{edges:[{edgeKey, fromAssetKey, fromColumnKey, toAssetKey, toColumnKey, transformationType, transformation, confidence, seenAt}]}` | table source → table de modèle (Power Query, niveau table + un lien par colonne passée telle quelle, renommages suivis), colonne → colonne calculée ou mesure et mesure → mesure (DAX), modèle → rapport, rapport ou modèle → tableau de bord (tuiles), dataflow → modèle, source → entité de dataflow, modèle → modèle (composite) |
| `usage` | `{items:[{assetKey, queryCount30d}]}` | événements `ViewReport` / `ViewDashboard` de `admin/activityevents` sur `days` jours (≤ 30) |
| `refreshes` | `{jobs:[{jobKey, namespace, name, orchestrator, assetKey, lastRunAt, lastRunStatus, runs:[{externalRunId, startedAt, endedAt, status, message}]}]}` | historique de rafraîchissement des modèles (`datasets/{id}/refreshes`) et dataflows (`transactions`) lisibles par le principal |
| `workspaces` | `{items:[{id, name, type}]}` | entrée facultative `filter` |
| `query` | `{columns, rows, count}` | `executeQueries` sur un modèle sémantique : entrées `dataset` (id ou `espace/nom`) et `dax` ; les clés `Table[Colonne]` réduites à `Colonne` |
| `scan` | le document brut du Scanner API | diagnostic |

**Noms des actifs** : `<espace>/reports/<nom>`, `<espace>/dashboards/<nom>`,
`<espace>/models/<modèle>` et `<espace>/models/<modèle>.<table>`, `<espace>/dataflows/<nom>` et
`<espace>/dataflows/<nom>.<entité>`.

**Raccorder le lineage aux autres sources** — propriété `datasource.map`, une entrée
`serveur[/base]=SOURCE[:upper|:lower]` par ligne (ou séparées par `;`). Une source Power Query qui
correspond est clé `SOURCE:<schéma>.<table>` (noms en majuscules ou minuscules selon l'orthographe
de cette source), soit exactement la clé produite par le scan de cette source : le lien arrive sur
la vraie table. Exemple : `xy12345.snowflakecomputing.com=DWH:upper`. Une source non mappée devient
un actif `TABLE` de la source Power BI elle-même, `datasources/<serveur>[/<base>]/<schéma>.<table>`,
avec les colonnes déduites des tables de modèle qui la lisent (`external.assets=false` supprime ces
liens à la place).

Le lecteur Power Query comprend les enregistrements de navigation des sources usuelles
(`Sql.Database`, `Snowflake.Databases`, `Databricks.Catalogs`, `GoogleBigQuery.Database`,
`PostgreSQL`, `MySQL`, `Oracle`, fichiers, `PowerPlatform.Dataflows`, `AnalysisServices`), les
paramètres et expressions partagées du modèle, `[Query=…]` / `Value.NativeQuery` (tables lues dans
`FROM` / `JOIN`), `Table.RenameColumns`, `Table.AddColumn`, `Table.RemoveColumns`, et type le lien
de table d'après les étapes : `Table.SelectRows` → `FILTER`, `Table.Group` → `AGGREGATION`,
`Table.NestedJoin` / `Table.Combine` → `JOIN` (avec un lien depuis la requête jointe quand c'est une
table du même modèle), autre `Table.*` → `TRANSFORMATION`, renommages et changements de type seuls
→ `IDENTITY`. Le lecteur DAX relève les références `Table[Colonne]`, `'Nom de table'[Colonne]` et
`[Mesure]` nues (chaînes et commentaires ignorés) ; une mesure qui appelle un agrégat (`SUM`,
`CALCULATE`, `COUNTROWS`…) donne `AGGREGATION`. Ce qui n'est pas compris produit un lien niveau
table, jamais une erreur, et une ligne de log par table non résolue.

Autres propriétés : `include.personal`, `include.hidden`, `dataflow.definitions`, `scan.timeout`
(secondes), `max.workspaces`, `portal.url` (URL des actifs), `max.rows` (requête DAX). Vérifié hors
ligne seulement : `PowerBiScanTest` (un résultat de scan enregistré → documents catalogue et lineage
exacts) et `PowerBiConnectorTest` (un faux tenant : jeton, interrogation du Scanner API, journal
d'activité, historique de rafraîchissement, requête DAX, repli standard), tous deux dans
`build-test.sh`. Rien n'a tourné contre un vrai tenant.

### 3.6 Collibra, dans les deux sens : le type `collibra`

`CollibraConnector` dialogue avec une Collibra Data Intelligence Platform par son API REST 2.0
(`https://<instance>.collibra.com/rest/2.0`, le suffixe est ajouté au `baseUrl`). `authType` =
`BASIC` (un utilisateur Collibra avec accès API) ou `BEARER` / `OAUTH2` (un JWT du fournisseur
d'identité de l'instance). Aucune bibliothèque. Si la liste blanche sortante est appliquée,
autoriser l'hôte de l'instance.

Tout ce que Collibra identifie par UUID est désigné **par nom** : communautés, domaines, types
d'actifs, types d'attributs, statuts, et types de relations écrits `Type source>rôle>Type cible`
(correspondance exacte d'abord, puis rôle + type cible, puis rôle seul — le choix est journalisé ;
un UUID est accepté partout où un nom l'est). Les noms sont résolus une fois et mis en cache par
instance de connecteur. Propriétés : `community` (où les domaines sont créés à la demande et les
noms résolus), `domain.type` (`Physical Data Dictionary`), `term.type` (`Business Term`),
`relation.contains` (`Table>contains>Column`), `relation.lineage` (`Column>is source for>Column`),
`relation.lineageTable` (`Table>is source for>Table`), `relation.term`
(`Column>represents>Business Term`), `attribute.description` / `definition` / `dataType` / `note` /
`url` (alias des noms de types d'attributs : `Description`, `Definition`, `Technical Data Type`,
`Note`, `URL`), `max.rows`. Vérifier les noms d'une instance avec `types` (`kind` = `assetTypes`,
`attributeTypes`, `relationTypes`, `statuses`, `communities`, `domains`, `domainTypes`).

| Opération | Sens | Entrées / charge | Document |
|---|---|---|---|
| `search` | entrant | `name` (+ `match` = EXACT, START, ANYWHERE), `type`, `domain`, `community`, `status`, `limit`, `attributes=true` | `{items:[{id, name, displayName, type, domain, community, status, url, attributes?}]}` |
| `get` | entrant | `id`, ou `name` + `domain` (+ `community`) | un actif avec `attributes` et relations `outgoing` / `incoming` |
| `terms` | entrant | `type` (défaut `term.type`), `domain`, `community`, `name`, `limit` | `{items:[{id, code, name, definition, description, note, status, domain, community, url}]}` — un feed de glossaire |
| `types` | entrant | `kind`, `name` | `{items:[{id, name, spec?...}]}` |
| `upsert` | sortant | `name`, `displayName`, `type`, `domain` (créé à la demande), `community`, `status`, `parent` (+ `parentRelation`), `term` (+ `termRelation`), `attributes.*`, `relations[]` | `{id, name, created, attributesSet, relationsCreated, url}` |
| `relate` | sortant | `sourceId` / `targetId`, ou `source` + `sourceParent` + `sourceDomain` et idem pour `target` ; `type` | `{created, sourceId, targetId}` ou `{created:false, skipped}` |
| `import` | sortant | charge = un tableau de commandes d'import Collibra ; `wait`, `timeout`, `simulation` | `{jobId, state, message, result?}` |
| `delete` | sortant | `id` | `{deleted}` |

`upsert` identifie un actif par **son nom dans son domaine** ; avec un `parent`, le nom devient
`<parent> > <nom>` et le nom d'affichage le nom nu — la convention de l'ingestion catalogue de
Collibra, qui garde les noms de colonnes uniques dans un domaine. Les attributs ne sont créés ou
modifiés que s'ils diffèrent ; les relations ne sont créées que si elles manquent. Entrées et champs
de la charge portent les mêmes noms et l'entrée l'emporte : un mapping sortant règle tout par ses
règles (`./qualifiedName → name`, `./description → attributes.description`, `./asset → parent` avec
un `lookup`…) et une entrée de mapping ajoute une constante. Vérifié hors ligne seulement
(`CollibraConnectorTest`, un Collibra en mémoire construit d'après la référence publique de l'API) :
aucune instance réelle appelée — les noms de types de relations et d'attributs du modèle
opérationnel du client sont le premier point à vérifier avec `types`.

### 3.7 Le lineage de chaque flux : le type `openlineage`

Déclarer **un** connecteur de type `openlineage` — URL de base = le point d'entrée qui accepte des
`RunEvent` [OpenLineage](https://openlineage.io) (`http://hôte/ebx-data-catalog/rest/v1/openlineage`
pour le Data catalog ON EBX, `/api/v1/lineage` de Marquez, l'ingestion de DataHub…), avec son
authentification — et chaque **exécution sortante** du module s'y décrit d'elle-même, sans autre
paramétrage : un événement par mapping et par exécution (`beginRun()` / `endRun()` : appel REST,
étape de workflow, tâche planifiée, service utilisateur ; un déclencheur de table ou un appel isolé
envoie le sien). Le `job` est le mapping (espace `job.namespace`, par défaut `ebx-connectors`, nom =
le libellé) ; l'entrée est la table EBX nommée comme dans le catalogue (espace `ebx.namespace` =
**le `lineageNamespace` de la source EBX dans le catalogue**, par défaut `ebx://localhost:8080`, nom
`<dataspace>/<dataset><tablePath>`) ; la sortie est l'objet du connecteur (espace déduit de son URL de
base, `h2://localhost:9092/catalogdemo`, `snowflake://…`, `salesforce://…` ; nom = l'entrée `table`
(préfixée du `schema` du connecteur), `object`, `topic`…, sinon l'opération ; sur un broker — `kafka`,
`rabbitmq`, `jms`, `activemq`, `azure-service-bus` — l'entrée `topic` / `queue`, sinon la propriété
`topic` du connecteur, jamais l'entrée `table` qui n'y est qu'un libellé ; une URL sans schéma
`localhost:9082` donne `kafka://localhost:9082`) ; `columnLineage` porte
une entrée par règle (identité ou transformation avec le pipeline, les constantes exclues) ; **chaque
mapping de sous-objet, à toute profondeur, apporte sa propre table EBX** (dataset et dataspace de la
racine s'il n'en a pas) : en sortie, la table fille est une entrée de plus et les champs
`collection[].champ` du document pointent vers elle, pas vers la table racine ; en entrée, la table
fille est une sortie de plus dont les champs pointent vers `collection[].champ` de l'objet du
connecteur ;
`outputStatistics.rowCount` = enregistrements envoyés, `errorMessage` et `FAIL` quand rien n'est
passé, `ebx_connectors` = les compteurs. Le `runId` OpenLineage dérive de l'id d'exécution et du
mapping : un rejeu met à jour la même exécution. Propriétés : `emit.outbound` (vrai), `emit.inbound`
(faux : imports et enrichissements décrits aussi, EBX en sortie), `exclude.types`
(`chat-webhook,slack,openlineage,llm`), `exclude.connectors`, `producer`. Rien n'est envoyé pour une
simulation ou une exécution sans envoi ; un puits qui refuse un événement est journalisé (`WARN`) et
ne fait jamais échouer la synchronisation. L'opération `emit` poste un événement construit à la main.

#### Lineage déclaré : avant la première exécution

Un flux apparaît dans le catalogue **dès qu'il est déclaré**, pas seulement après avoir tourné. Les
déclencheurs de `Mapping`, `MappingInput` et `MappingRule` (`MappingCascadeTrigger`,
`MappingPartLineageTrigger`) confient le mapping à `core/DeclaredLineage`, qui le décrit deux secondes
après la dernière modification, hors transaction (un enregistrement du concepteur ou un import de
configuration donne un événement par mapping, lu sur la définition validée) : un mapping racine
**actif** part en **`JobEvent`** OpenLineage (ni `run` ni `eventType` ; même job, mêmes datasets et
même lineage colonne qu'une exécution, plus la facette `ebx_connectors_declaration`, `state`
`DECLARED`) ; un mapping de sous-objet est décrit avec sa racine, sa table comprise, en entrée comme
en sortie ; les écritures faites **sans déclencheurs** programment elles-mêmes la même déclaration
(outils MCP `connectors_mapping_upsert` et `connectors_mapping_delete`, REST `DELETE /mappings/{id}`,
la suppression décrivant aussi de nouveau la racine du sous-objet) ; un mapping **désactivé ou
supprimé**, et l'**ancien nom** d'un mapping renommé, part en retrait (`JobEvent` sans dataset,
`state` `RETIRED`). Au **démarrage** tous les mappings sont balayés une fois (après 90 s, deux
nouvelles tentatives à deux minutes quand un puits ne répond pas encore), ce qui rattrape les
mappings existants ; **à la demande** : `POST /ebx-connectors/rest/v1/lineage/declare[?mapping=<id ou
libellé>]` ou l'outil MCP `connectors_lineage_declare`, après l'ajout d'un puits ou le changement de
l'URL d'un connecteur (non surveillé). Le catalogue prend un job event pour **tout le lineage actuel**
du job : arêtes en origine `DECLARED` (confiance 90) sauf si déjà observées, arêtes du job qu'il ne
cite plus désactivées, dernière exécution du job inchangée ; la première exécution les fait passer en
`OPENLINEAGE`. Propriété du puits `emit.declared` (vrai) ; sens et exclusions comme pour les
exécutions. Propriétés système : `ebx.connectors.lineage.declared` (vrai),
`ebx.connectors.lineage.declareOnStartup` (vrai), `ebx.connectors.lineage.startupDelaySeconds` (90),
`ebx.connectors.lineage.debounceMs` (2000). Les déclencheurs sont déclarés dans le modèle : effectifs
après rechargement du modèle Connectors et redémarrage de Tomcat.

### 3.8 Une instance EBX dans le Data catalog : le type `ebx`

Le connecteur `ebx` (une autre instance par Data Services, ou **celle-ci** par son propre
`http://localhost:8080/ebx-dataservices/rest/data/v1`) porte les quatre opérations de catalogue, dans
les documents que produit `JdbcConnector` : `catalog` (tables et colonnes lues dans les métamodèles
`?includeMetamodel=true`, comptage `:count` ; entrées `source`, `schemas` = clés de dataspaces ou
`dataspace/dataset`, `exclude`, `rowCounts` ; noms `<dataspace>/<dataset><tablePath>` et
`Groupe/champ`), `profile` (statistiques sur les premiers `sampleSize` enregistrements, comptage
exact), `sample` (valeurs des colonnes texte) et `lineage` (les clés étrangères des modèles en arêtes
colonne, `fkScope` = `cross` ou `all`, origine `PLATFORM`). Lecture seule.

**Vitesse.** Quand `tables` donne des noms qualifiés complets (`Companies/Companies/root/Companies`), les
quatre opérations lisent directement ces tables — le métamodèle, le comptage et, pour `profile` / `sample`,
les enregistrements : 3 appels Data Services pour une table — au lieu de parcourir d'abord tous les
dataspaces ; `schemas` et `exclude` s'appliquent toujours, et un nom partiel (`Companies`) revient au
parcours. Avant, reprofiler une table lisait le métamodèle et le comptage des 213 tables de l'instance
(environ 430 appels, 31 s). Les lectures par table d'un parcours s'exécutent en parallèle, résultats dans
l'ordre de la sélection : `-Debx.connectors.ebx.catalogThreads` (4 par défaut, `1` = une à la fois, 16 au
plus). Mesuré à chaud sur 639 tables : 12,7 s sur un thread, 5,4 à 7,3 s sur quatre, pas mieux sur huit.

### 3.9 Un cluster Kafka dans le Data catalog : le type `kafka`

Le connecteur `kafka` porte trois opérations du Data catalog, dans les documents du type `ebx`. Un topic ne
déclare aucun schéma et n’exécute aucune requête : tout est lu dans ses **derniers messages**, en lecture
seule — les opérations affectent les partitions à un consommateur sans groupe, repartent des offsets de fin
et ne commitent rien.

| Opération | Entrées | Document |
|---|---|---|
| `catalog` | `source` ; `schemas` = noms de topics ou motifs avec `*`, séparés par des virgules (vide ou `*` = tous les topics) ; `exclude` (regex sur le nom du topic, par défaut sans `__consumer_offsets`, `_schemas`, `_confluent*` ni les topics de stockage de Kafka Connect) ; `sampleSize` (1000 par défaut) ; `rowCounts` | `tables[]` : nature `TOPIC`, `qualifiedName` = `name` = le topic, `rowCount` = messages retenus (offsets de fin − de début), `url` = `kafka://<premier serveur bootstrap>/<topic>`, `remarks` indiquant de combien de messages viennent les champs ; `columns[]` **déduites** de ces messages : objets imbriqués aplatis (`address/city`), tableaux `JSON`, nombres `INTEGER` / `DECIMAL`, textes ISO `DATE` / `TIMESTAMP`, types mêlés `STRING`, `nullable` quand un message n’a pas le champ ; `@key` et `@timestamp` décrivent le message, `@value` une charge qui n’est pas un objet JSON |
| `profile` | + `tables`, `sampleSize` | un élément par champ, calculé par ON EBX sur les derniers messages : `method` = `SAMPLE`, `sampleSize`, `rowCount` = messages retenus, `nullCount` ramené à ce nombre |
| `sample` | + `tables`, `limit` (50 par défaut) | valeurs distinctes des champs texte |

Un topic illisible (autorisation, délai) est listé dans `errors` et les autres continuent ; si aucun n’est
lisible, l’opération échoue. Un champ qu’aucun des messages lus ne porte n’est pas listé, et un topic sans
message retenu n’a aucun champ.

**Table entière ou échantillon.** Les profils de `jdbc`, `snowflake` et `databricks` sont calculés par la
source sur toutes les lignes (`method` = `FULL_SCAN`) ; ceux de `ebx` et `kafka` par ON EBX sur les
enregistrements lus (`core/RecordProfiler`, `method` = `SAMPLE`) : taux de nuls, valeurs distinctes, min / max,
moyenne et longueurs ne décrivent que ces enregistrements. Le Data catalog garde la méthode sur chaque
instantané et le signale sur l’actif.

### 3.10 Slack avec un jeton de bot : le type `slack`

Le type `chat-webhook` écrit dans un seul canal par l'URL d'un webhook entrant, et cette URL est le secret :
elle est dans l'URL de base, en clair. Le type `slack` appelle l'API Web de Slack (`https://slack.com/api`) avec
le **jeton de bot** d'une application Slack, rangé dans `apiKey` (chiffré, masqué comme tout secret). Un seul
connecteur atteint tous les canaux où l'application peut écrire, et un message posté peut être modifié ensuite.

**Mise en place.** Sur api.slack.com/apps, ouvrez ou créez l'application, puis *OAuth & Permissions* > *Bot
Token Scopes* : `chat:write`, `chat:write.public` (écrire dans un canal public sans y inviter l'application),
`channels:read` (noms de canaux, `channels`), `users:read.email` (`dm`), et `groups:read` pour les canaux privés.
*Install to Workspace*, copiez le *Bot User OAuth Token* (`xoxb-...`) dans le connecteur amorcé `Slack`, et
renseignez sa propriété `channel` (canal par défaut, `#nom` ou identifiant). *Tester la connexion* appelle
`auth.test`, qui ne modifie rien.

| Opération | Entrées | Document |
|---|---|---|
| `post` (W) | `text` (obligatoire), `channel` (sinon la propriété), `title` (première ligne en gras), `threadTs` (répondre dans un fil), `replyBroadcast`, `blocks` (tableau JSON Block Kit) | `status` = `sent`, `channel` (identifiant), `ts` (identifiant du message), `threadTs` |
| `notify` (W) | comme `post` | comme `post` : un flux écrit pour `chat-webhook` passe sur Slack en changeant seulement son connecteur |
| `update` (W) | `ts`, `text` (obligatoires), `channel`, `title`, `blocks` | `status` = `updated`, `channel`, `ts` — uniquement un message posté par cette application |
| `dm` (W) | `text` (obligatoire), `email` (ou `user`, identifiant du membre), `title`, `blocks` | `status`, `user`, `channel` (la conversation directe), `ts` |
| `channels` (S) | `name` (contient, facultatif), `types` (par défaut la propriété `channelTypes`, sinon `public_channel`), `max` | `count`, `items[]` : `id`, `name`, `isPrivate`, `isMember`, `isArchived`, `members`, `topic`, `purpose` |

Un canal se donne par identifiant (`C0123ABCD`) ou par nom (`#data-alerts`) ; les noms sont résolus une fois
par `conversations.list` et gardés jusqu'au rechargement du connecteur. `&`, `<` et `>` sont échappés, sauf si
la propriété `escapeText` vaut `false` (pour garder les mentions `<@U123>` ou les liens `<url|libellé>`) ; les
aperçus de liens restent désactivés sauf si `unfurlLinks` vaut `true`. Un mapping sortant peut aussi construire
le document : `text`, `title`, `channel`, `threadTs` et `blocks` y sont lus quand les entrées les laissent vides.
Enregistrer le `ts` renvoyé sur l'enregistrement (mapping de réponse) permet un `update` plus tard.

**Refus.** Slack répond à la plupart des refus par un HTTP 200 et `{"ok": false, "error": "..."}`. Le connecteur
vérifie `ok` à chaque réponse : un refus fait échouer l'exécution au lieu de compter comme envoyé, avec le code
d'erreur de Slack, un statut (401 jeton, 403 `missing_scope` / `not_in_channel`, 404 `channel_not_found` /
`users_not_found`, 429 limite de débit) et la marche à suivre : le scope que Slack dit manquant, `/invite` de
l'application, vérifier le jeton. Slack accepte environ un message par seconde par canal. Contrat hors ligne :
`SlackConnectorTest`.

**Les tâches de workflow dans Slack.** Mettez `ebx-connectors@com.on.ebx.connect.ebx.workflow.ChatWorkItemTrigger`
comme déclencheur d'un modèle de workflow (champ `workflowTrigger`), puis republiez-le. Chaque nouvelle tâche est
postée avec un lien qui l'ouvre dans l'inbox EBX de l'utilisateur
(`/ebx/?service=@workflow&workflowView=inbox&xpath=[tâche]`), à qui elle est offerte et le workflow ; quand la
tâche se termine, le message devient « Acceptée par Ana » ou « Rejetée par Ana ». La décision se prend dans EBX,
dans la session de l'utilisateur : EBX enregistre la bonne personne et applique ses droits, et Slack n'appelle
jamais EBX. Le connecteur est `Slack` (`-Debx.connectors.workItemNotifier.connector` pour un autre) et ses
propriétés le pilotent : `workflow.channel` (par défaut `channel`), `workflow.channel.<rôle>` (par exemple
`workflow.channel.Steward = #stewards`), `workflow.baseUrl` (l'adresse par laquelle les utilisateurs ouvrent EBX,
par défaut `http://localhost:8080`) et `workflow.startWorkItem` (par défaut `true` : le lien prend la tâche). Le
canal et le `ts` de chaque message sont gardés dans le journal de synchronisation (run id `workitem:<clé de la
tâche>`) ; si le message est introuvable, le résultat est posté dans un nouveau message. Un échec de notification
n'arrête jamais le workflow. Un utilisateur qui ouvre le lien d'une tâche qui ne lui est pas offerte voit « The work
item to select has not been found in your 'Inbox' folder ». Contrat hors ligne : `WorkItemNoticesTest`.

## 4. Mappings

### 4.1 Champs de la table `Mapping`

| Champ | Rôle |
|---|---|
| `connector`, `operation`, `direction` | quel connecteur, quelle opération (liste calculée depuis le connecteur), quel sens |
| `label` | nom libre ; utilisable à la place de l'id dans l'API REST et le bean de workflow |
| `dataspace`, `dataset`, `tablePath` | où il s'applique ; `dataspace` vide = partout où le jeu de données existe (espaces enfants de workflow compris) |
| `responseRoot` | chemin JSON appliqué au document **avant** les règles, ex. `data.attributes` (GLEIF), `organization` (D&B) |
| `active` | un mapping inactif n'est jamais sélectionné |
| `parent`, `collectionPath`, `parentLinkField`, `childKeyFields`, `childFilter`, `deleteMissing` | sous-objets (§ 8) |
| `itemsPath`, `keyPredicate`, `createIfMissing` | flux d'import (§ 9) |
| associations `inputs`, `rules`, `children` | entrées, règles, mappings enfants |

### 4.2 Le cycle d'un mapping entrant sur un enregistrement

1. Les **entrées** sont calculées depuis l'enregistrement (§ 5). Si une entrée **obligatoire** de
   l'opération est vide, l'appel est **SKIPPED** (« Input 'lei' is empty on this record »).
2. Le connecteur exécute l'opération ; `null` = **NO_DATA**.
3. `responseRoot` est appliqué au document.
4. Chaque **règle** lit sa source dans le document, applique son pipeline, et écrit sa cible selon sa
   **politique** : `IF_EMPTY` n'écrit que si le champ EBX est vide, `ALWAYS` écrase, `TRUSTED` arbitre par la confiance (voir 4.4).
5. Les **sous-objets** sont traités (§ 8), puis tout est enregistré en une transaction, et une ligne
   `SyncLog` est écrite : **OK** (au moins un changement), **NO_CHANGE**, ou **ERROR**.

Le même mapping s'exécute en **simulation** (`dryRun`) : les changements sont calculés et montrés,
rien n'est écrit.

### 4.3 Exemple minimal : GLEIF, fiche par LEI

Connecteur `GLEIF`, opération `lei` (lecture, entrée obligatoire `lei`), sens entrant, table
`/root/Companies`, `responseRoot = data.attributes` :

| Entrée | Expression |
|---|---|
| `lei` | `${./Identification/LegalEntityIdentifier}` |

| Source (document) | Cible (EBX) | Transformation | Politique |
|---|---|---|---|
| `entity.legalName.name` | `./LegalInformation/legalName` | `trim\|left(255)` | IF_EMPTY |
| `entity.legalForm.id` | `./LegalInformation/legalForm` | `fk(/root/ReferenceData/LegalForm)` | IF_EMPTY |
| `registration.status` | `./Lifecycle/leiStatus` | | IF_EMPTY |
| *(vide)* | `./Governance/enrichmentSource` | `constant(GLEIF)` | IF_EMPTY |

Une règle **sans source** est une constante : sa valeur vient du pipeline (`constant(...)`).

### 4.4 Confiance, provenance et conflits

Quand deux sources divergent, le module tranche par la **confiance** :

- chaque connecteur porte un **niveau de confiance** de 0 à 100 (`Connector/trust`). Valeurs amorcées :
  GLEIF, Sirene, Companies House 90 ; D&B, Orbis, Handelsregister 85 ; VIES, Creditsafe, OpenSanctions 80 ;
  Melissa, Loqate 75 ; BAN, OpenCorporates, SAP, JDE 70 ; Google, CRM et ERP 60 ; Wikidata 40 ; défaut 50.
  Une saisie manuelle, ou une valeur chargée hors connecteurs, vaut **100** ;
- chaque écriture d'un connecteur est mémorisée dans la table **`FieldProvenance`** (enregistrement, champ,
  connecteur, confiance, valeur, date), quelle que soit la politique de la règle : c'est la piste d'audit ;
- la politique de règle **`TRUSTED`** écrit si le champ est vide, ou si la source est **au moins aussi
  fiable** que celle qui a écrit la valeur en place (une source se rafraîchit donc elle-même, et une
  meilleure corrige une moins bonne). Sinon la proposition est consignée dans la table **`Conflict`**
  (valeur en place, qui l'a écrite et avec quelle confiance, valeur proposée, par qui, quand), statut `OPEN`,
  pour qu'un steward tranche : appliquer la valeur à la main et passer le conflit à `ACCEPTED`, ou le passer
  à `REJECTED`. Un même conflit n'est consigné qu'une fois tant qu'il est ouvert.

`IF_EMPTY` et `ALWAYS` restent disponibles et inchangés. Le rapport d'un appel (journal, API REST, bean de
workflow) compte les conflits, et l'API les détaille (`results[].conflicts`).

Exemple : le nom légal de Microsoft France a été écrit par VIES (confiance 80). L'API Recherche
d'entreprises (85) propose « MICROSOFT FRANCE » : avec `TRUSTED` elle l'emporte et écrase. Wikidata (40)
proposant autre chose serait consigné en conflit sans rien changer. Un nom corrigé à la main (100) n'est
plus jamais écrasé par un connecteur.

### 4.5 Gérer les conflits

Un conflit est une décision de steward. Trois moyens de savoir qu'il y en a :

- **API REST** : chaque réponse de `/enrich`, `/sync/{mapping}` et `/webhook/{feed}` porte `conflicts`
  (nombre) et `hasConflicts` (booléen), et chaque résultat détaille les siens dans `results[].conflicts`.
  `GET /ebx-connectors/rest/v1/conflicts?dataset=Companies&table=/root/Companies&key=513` liste les
  conflits ouverts (`status=ACCEPTED|REJECTED` pour l'historique) ;
- **tâche script de workflow** : le bean renvoie `conflictCount` et `hasConflicts` (`true`/`false`), à
  tester dans une condition (`ConditionIsValueEqualsTo`, `value1=${hasConflicts}`, `value2=true`) pour
  router vers la tâche utilisateur ci-dessous ;
- **journal** : le message de chaque appel compte les conflits.

L'écran **« Gérer les conflits des connecteurs »** (User Service du module) liste les conflits ouverts
avec, pour chaque champ, la valeur en place (qui l'a écrite, sa confiance) et la valeur proposée (par
qui, sa confiance). Pour chaque ligne : *Prendre la valeur proposée* (la valeur est écrite dans
l'enregistrement, la source proposante devient la provenance du champ, le conflit passe à `ACCEPTED`),
*Garder la valeur en place* (`REJECTED`) ou *Décider plus tard*. Un commentaire commun est enregistré sur
les conflits tranchés, avec l'utilisateur et la date. Il est disponible :

- sur un **espace de données** (menu Actions, ou action de perspective) : tous les conflits de l'espace ;
- sur une **table** (menu Actions de la vue) : ceux de la table ;
- sur un **enregistrement** : ceux de l'enregistrement ;
- sur la **table Conflict du jeu de données Connectors** elle-même : tout le stock de conflits ouverts, ou
  seulement les conflits sélectionnés dans la vue (le service sur un enregistrement de conflit ouvre ce seul conflit) ;
- comme **tâche utilisateur de workflow** (service `ebx-connectors@ConflictManager`) : paramètres
  d'entrée `branch` (obligatoire), `dataset`, `table`, `recordKey` (optionnels, ils restreignent la liste) ;
  sorties `resolvedCount` et `remainingCount`. Le bouton *Terminer la tâche* clôt le work item.

En REST, la décision se prend avec `POST /conflicts/{id}/accept?comment=...` ou `.../reject`.

Le workflow `ConnectorEnrichment` enchaîne ainsi : tâche script d'enrichissement, condition sur
`hasConflicts`, tâche utilisateur « Résoudre les conflits » proposée aux administrateurs.

### 4.6 Supprimer un mapping

Un mapping a des éléments rattachés : règles, entrées, sous-objets, états d'envoi, et des lignes de
provenance et de conflit qui le référencent. Supprimer la seule ligne Mapping les laisserait orphelins :
la table Mapping porte donc un **trigger de cascade** (`MappingCascadeTrigger`). Quelle que soit la
façon dont un mapping est supprimé (interface, Data Services, import, service), ses règles, entrées,
sous-objets et états d'envoi partent avec lui, et les lignes de provenance et de conflit gardent leur
historique mais perdent la référence. Le journal de synchronisation est conservé tel quel.

Le service utilisateur **Supprimer ce mapping et ses éléments rattachés** (sur un enregistrement
Mapping, ou sur les lignes sélectionnées de la table Mapping) montre ce qui sera supprimé et ce qui
sera seulement délié, puis demande confirmation. La même suppression existe en
`DELETE /ebx-connectors/rest/v1/mappings/{id}` et par l'outil MCP `connectors_mapping_delete`.
Exporter la configuration d'abord (`GET /rest/v1/config`) si le mapping peut resservir.

## 5. Entrées et expressions

Une entrée = `nom` (celui attendu par l'opération) et `expression`. La syntaxe des expressions :

| Forme | Résultat |
|---|---|
| `${./Address/City}` | la valeur du champ de l'enregistrement, au format XML Schema (dates ISO, FK = clé) |
| `${./A} ${./B}` | juxtaposition : concaténation avec le texte autour |
| `${param.max}` | un paramètre passé à l'exécution (workflow, API) |
| `75008` ou `FR` | une constante |
| `${./Address/City\|capitalize\|left(40)}` | un pipeline sur **un** placeholder |
| `${./Address/Street} ${./Address/Zipcode} \|> trim\|upper` | un pipeline sur la **valeur assemblée** (`\|>`) |

Les transformations disponibles sont celles des règles (§ 7), `map`, `fk` et `lookup` compris. Une
transformation inconnue fait échouer l'appel avec un message explicite. Exemples :

```
vat      = ${./LegalInformation/registrationNumber|vatfr}                 (SIREN -> FR + clé + SIREN pour VIES)
name     = ${./Identification/Name|regex('^(.*?)(?:[,\s]+(?:Inc\.?|SAS|SA|GmbH|Ltd\.?))?\s*$',1)}   (nom sans forme juridique)
address  = ${./Address/Street} ${./Address/Zipcode} ${./Address/City}     (adresse sur une ligne pour BAN)
```

Dans le concepteur, le panneau **Appel du service** liste les entrées de l'opération (obligatoires
marquées d'une étoile), avec un sélecteur de champ EBX et des puces de transformation.

Les placeholders peuvent être **imbriqués** : `${./LegalInformation/registrationNumber|default(${./Identification/Name|upper})}`
envoie le SIREN s'il existe, sinon le nom en majuscules. Le placeholder intérieur est résolu d'abord, puis
sert d'argument à la transformation extérieure.

**Entrées d'exécution d'un flux.** Un flux ne part d'aucun enregistrement : ses entrées sont donc des
constantes, sauf si une expression lit un paramètre d'exécution. Avec `tables = ${param.tables|default(*)}`,
une exécution sans paramètre lit toutes les tables, et une exécution qui reçoit `tables=SALES.ORDERS` ne lit
que celle-là. Les entrées d'exécution sont du texte, des paires `nom=valeur` séparées par `;`
(`tables=SALES.ORDERS,SALES.CUSTOMER;limit=50`), acceptées par toutes les façons de lancer un flux :
`inputs=` sur `POST /sync/{mapping}`, le paramètre `inputs` du bean de workflow et de la tâche planifiée,
et le paramètre d'URL `inputs` du service utilisateur (§ 12). **Seuls les noms qu'une expression d'entrée du
mapping lit en `${param.nom}` sont utilisés** ; les autres ne le sont pas, et chaque exécution le dit :
`ignoredInputs` (et `inputsIgnored`, gardé pour les premiers appelants REST), `warning: true` et une phrase
dans `message` dans les réponses MCP et REST, dont le `OK` simple devient alors `WARNING` (`PARTIAL`,
`ERROR`, `NO_DATA` et `RUNNING` restent tels quels) ; un message dans le service, à côté de tout autre
message de l'exécution ; la sortie `warnings` du bean de workflow et la première ligne de son `report` ;
une ligne d'exécution commençant par `WARNING:` dans le planificateur. Une exécution sur un
enregistrement, sur les enregistrements d'une table ou sur une portée sortante ne lit aucune entrée
d'exécution : toutes celles qu'on lui donne sont signalées de la même façon. Une entrée
d'exécution restreint ce que le mapping permet déjà ; elle n'ajoute jamais une entrée que le mapping
n'utilise pas : un lien ou un script ne peut pas glisser un `sql` ou un `where` dans un flux qui n'en a pas.

**Ce que prend une exécution (`max`).** `max` borne ce que prend UNE exécution : les enregistrements d'une
table ou d'une portée sortante, ou les éléments qu'importe un flux. Sa valeur par défaut dépend de la façon
de lancer l'exécution, et elle est définie à un seul endroit (`com.on.ebx.connect.core.RunLimits`) :

| Lancée depuis | Paramètre | Défaut |
|---|---|---|
| Planificateur (`ConnectorScheduledTask`) | `maxRecords` | 100 |
| MCP `connectors_sync` (et le bean en JVM), REST `POST /sync/{mapping}`, bean de workflow `ConnectorScriptTaskBean` | `max` / `maxRecords` | 200 |
| Service utilisateur, sur des enregistrements ou une portée | *Nombre maximal d'enregistrements* | 100 |
| Service utilisateur, sur un flux | *Nombre maximal d'enregistrements* | 499 (4 999 au plus pour une simulation) |
| REST `POST /webhook/{flux}` | `max` | 10 000 (c'est aussi le plafond) |
| Publication d'un data product (`connectors_publish`, `POST /publish`, `ProductPublishTaskBean`) | `max` / `maxRecords` | 0 = toutes les lignes |

Une exécution coupée à `max` le dit toujours, quelle que soit la façon de la lancer : un résultat « Limit
reached » clôt ses résultats (avec le nombre d'éléments renvoyés par la source quand le connecteur le
connaît), sa réponse porte `truncated: true` et la phrase dans `message`, son statut est `PARTIAL`, jamais
`OK`, le service affiche l'avis sous ses compteurs, le bean de workflow met `truncated` à `true`, et la tâche
planifiée échoue, sauf si elle résorbe un arriéré (une portée sortante, une table filtrée par un prédicat),
auquel cas elle le dit. Le `count` d'une réponse compte les enregistrements traités, pas cet avis. La
propriété `max.rows` d'un connecteur (1 000 pour une source JDBC) est autre chose : ce que renvoie le
connecteur appelé sans `max` (`connectors_call`, un aperçu) ; le moteur passe toujours son `max` au
connecteur quand il exécute un flux.

## 6. Règles et chemins

### 6.1 Chemin source (côté document)

Notation pointée sur le JSON, après `responseRoot` :

| Chemin | Désigne |
|---|---|
| `entity.legalName.name` | un champ imbriqué |
| `results[0].name` | le premier élément d'un tableau |
| `addressLines[*]` | **tous** les éléments (une liste, à réduire par `join`, `first`, `count`) |
| `claims.P856[0].mainsnak.datavalue.value` | n'importe quelle profondeur |
| *(vide)* | pas de source : la règle produit une constante |

### 6.2 Chemin cible (côté EBX)

Chemin relatif à l'enregistrement : `./Identification/Name`, `./Address/latitude`,
`./LegalInformation/legalForm` (une clé étrangère reçoit la **clé** de l'enregistrement référencé ;
pour une clé composée, la forme `a|b` d'EBX). Les valeurs voyagent en chaînes XML Schema : dates
`2026-09-05`, dates-heures `2026-09-05T18:07:22`, booléens `true`/`false`, décimaux avec point.

### 6.3 Politique et ordre

`IF_EMPTY` (par défaut) respecte la donnée existante ; `ALWAYS` écrase ; `TRUSTED` écrase seulement si la source est au moins aussi fiable que celle qui a écrit la valeur (4.4). Les règles s'appliquent dans
l'ordre de `position`. En sens sortant, source et cible s'inversent : source = champ EBX, cible = chemin
JSON du document à construire (`primaryAddress.addressLocality.name`).

`IF_EMPTY` écrit un champ une fois : quand un flux est relancé après un changement de sa source, un champ
déjà rempli garde sa valeur, et l'exécution répond `NO_CHANGE`. Un flux qui doit suivre sa source demande
`ALWAYS` sur les règles des champs qui changent, ses champs clés (ce que lit son prédicat de clé, les champs
clés enfants et le champ de lien parent d'un mapping de sous-objet) restant en `IF_EMPTY`. Sur un flux, la
politique est donc un choix : le concepteur de mapping demande la politique des règles ajoutées à un flux
ou à un mapping de sous-objet (avec cette explication), la donne à chaque nouvelle règle sauf aux champs
clés, et peut l'appliquer aux règles déjà présentes. `connectors_mapping_upsert` et l'import de
configuration gardent `IF_EMPTY` pour une règle envoyée sans politique, et leur réponse dit ce qui a été
appliqué : `rulePolicies` (règles par politique), `policyDefaulted` (règles écrites en `IF_EMPTY` faute de
politique) et, sur un mapping entrant, une note dans `notes` qui dit ce que cela implique.

## 7. Transformations

Un pipeline = des étapes séparées par `|` : `trim|left(80)`. Les arguments sont entre parenthèses,
séparés par des virgules ; un argument contenant une virgule, un `|` ou des espaces de bord se met
entre quotes : `join(', ')`, `prefix('GLEIF ')`.

| Transformation | Effet | Exemple |
|---|---|---|
| `trim`, `upper`, `lower` | espaces de bord, casse | `upper` : `fr` → `FR` |
| `capitalize` | initiales en majuscule, reste en minuscule (après espace, `-`, `'`) | `PARIS` → `Paris` |
| `left(n)`, `right(n)` | n premiers / derniers caractères | `left(80)` |
| `substring(début,fin)` | sous-chaîne, indices 0 | `substring(1,11)` |
| `replace(a,b)` | remplace `a` par `b` (texte, pas regex) | `replace(-,)` |
| `regex(motif,groupe)` | renvoie le groupe capturé (défaut 1), `null` si pas de correspondance | `regex('^(\d{9})',1)` |
| `digits` | ne garde que les chiffres | `327 733 184` → `327733184` |
| `vatfr` | SIREN (ou SIRET) → numéro de TVA français `FRkk` + SIREN | `327733184` → `FR95327733184` |
| `prefix(p)`, `suffix(s)` | ajoute avant / après | `prefix(DUNS:)` |
| `default(v)` | `v` si la valeur est vide | `default(OTHER)` |
| `constant(v)` | toujours `v` (règle sans source) | `constant(GLEIF)` |
| `nullifempty`, `nullif(v)` | `null` si vide / si égal à `v` | `nullif(N/A)` |
| `isodate`, `isodatetime` | tronque en `AAAA-MM-JJ` / `AAAA-MM-JJTHH:MM:SS` | `2026-06-29T08:50:16.000Z` → date |
| `date(formatEntrée,formatSortie)` | reformate une date (`SimpleDateFormat`) | `date(dd/MM/yyyy,yyyy-MM-dd)` |
| `map(A=B;C=D;*=défaut)` | table de correspondance, `*` = valeur par défaut | `map(Active=ACTIVE;Out of Business=CLOSED)` |
| `join(sép)` | joint une liste en texte | `addressLines[*]` + `join(', ')` |
| `first`, `count` | premier élément / taille d'une liste | |
| `boolean` | `true`/`1`/`yes`/`oui`/`active` → vrai | |
| `number`, `round(n)` | décimal (virgule acceptée), arrondi à n décimales | `round(7)` |
| `fk(/root/Table[,dataspace,dataset])` | garde la valeur **seulement si** l'enregistrement référencé existe (évite le blocage « clé étrangère inconnue ») ; la table est cherchée dans le dataset synchronisé, sinon en suivant la clé étrangère du modèle qui la référence (ex. `/root/Currency` dans l'espace Currencies) ; espace et dataset explicites priment | `fk(/root/ReferenceData/LegalForm)` |
| `lookup(/root/Table,./champ,./résultat)` | cherche la ligne dont `champ` = valeur et renvoie `résultat` (table de correspondance en données) | `lookup(/root/Companies,./Identification/LegalEntityIdentifier,./Identification/Id)` |
| `golden(/root/Table[,dataspace,dataset])` | la valeur est la clé primaire formatée d'un enregistrement ; la résout à la clé de son **enregistrement pivot** quand Match and Merge (§ 10.2) déclare l'enregistrement MERGED — enregistrement introuvable, table hors Match and Merge, ou état différent de MERGED : valeur inchangée | `golden(/root/Companies)` |
| `mdmState(/root/Table[,dataspace,dataset])` | l'état Match and Merge de cet enregistrement (`GOLDEN`, `MERGED`, `UNSET`, `PENDING_APPROVAL`, `DELETED`, ou `UNMANAGED` si la table n'est pas sous Match and Merge) ; `null` si vide ou introuvable | `mdmState(/root/Companies)` |

Une transformation Java supplémentaire s'enregistre par `Transformations.register("nom", ...)`.

## 8. Sous-objets (tables filles)

Un mapping **enfant** a un `parent` et décrit une ligne d'une **table fille** liée à l'enregistrement
parent. Champs :

| Champ | Rôle | Exemple |
|---|---|---|
| `collectionPath` | chemin JSON de la **collection** dans le document du parent. Un tableau → un enfant par élément ; **vide** → le document lui-même est l'unique élément ; chemin absent → rien | `claims.P946`, `entity.addresses`, *(vide)* |
| `parentLinkField` | clé étrangère de la table fille vers le parent | `./company`, `./fromCompany` |
| `childKeyFields` | champs qui identifient une ligne existante à mettre à jour (sinon création) | `./type` ou `./type,./City` |
| `childFilter` | prédicat XPath restreignant les lignes filles concernées | `./type='LEI'` |
| `deleteMissing` | supprimer les lignes filles absentes du document | `false` |

Les règles de l'enfant ont pour source des chemins **relatifs à l'élément** de la collection et pour
cible les champs de la table fille. Trois exemples réels :

- **Identifiant LEI** sous la fiche GLEIF : `collectionPath` vide, table `/root/CompanyIdentifier`,
  lien `./company`, clé `./type`, filtre `./type='LEI'` ; règles `constant(LEI)` → `./type`, `lei` →
  `./value`, `constant(GLEIF)` → `./issuingAuthority`.
- **ISIN depuis Wikidata** : `collectionPath = claims.P946` (un tableau, souvent absent → aucune ligne
  créée) ; règle `mainsnak.datavalue.value` → `./value`.
- **Maison mère GLEIF** : `collectionPath` vide sur le document `ultimateParent`, table
  `/root/CompanyRelationship`, lien `./fromCompany`, clé `./type` ; règle `lei` → `./toCompany` avec
  `lookup(/root/Companies,./Identification/LegalEntityIdentifier,./Identification/Id)` : la relation
  pointe sur la société parente **si** elle est dans le référentiel.

En sens sortant, les enfants **construisent** la collection du document (adresses et contacts d'une
société envoyés à Kafka ou SAP en un seul document).

Dans le concepteur, la section **Sous-objets** crée un enfant (collection, table fille, champ de lien,
clés) ; on ouvre ensuite l'enfant pour dessiner ses règles.

## 9. Flux d'import

Quand l'opération renvoie une **liste** (Kafka `consume`, Snowflake/Databricks/SAP `query`, un fichier
`read`, le webhook `receive`), le mapping devient un flux :

| Champ | Rôle | Exemple |
|---|---|---|
| `itemsPath` | chemin JSON du tableau des éléments | `messages[*].value`, `rows`, `results`, `items` |
| `keyPredicate` | prédicat XPath qui localise l'enregistrement cible, avec `${chemin.json}` résolus depuis l'élément | `./LegalInformation/registrationNumber='${siren}'` |
| `createIfMissing` | créer l'enregistrement quand le prédicat ne trouve rien | `true` |

Chaque élément est traité dans sa propre transaction, puis ses sous-objets. Exemple, le webhook :
un `POST` avec `{"items":[{"siren":"327733184","name":"...","city":"...","country":"FR"}]}` crée
ou met à jour une société par SIREN, règles `siren` → `./LegalInformation/registrationNumber` (`digits`),
`name` → `./Identification/Name`, `country` → `./Address/Country` (`upper`)…

## 10. Sens sortant et mapping de réponse

Un mapping **sortant** construit un document depuis l'enregistrement (règles source = champ EBX, cible =
chemin JSON, sous-objets = collections) et l'envoie par une opération d'écriture (`upsert`, `publish`,
`merge`, `create`). Ce que le service **répond** (un identifiant créé, un statut) est stocké par le
**mapping de réponse** : un mapping *entrant*, même connecteur, même opération, même table, que le
cadre applique automatiquement à la réponse. Le concepteur l'affiche et le bouton **Créer le mapping de
réponse** le crée. Pour une opération de **lecture** (Melissa `address`, VIES, GLEIF), un seul mapping
entrant suffit : ses entrées sont l'envoi, ses règles la réponse.

### 10.1 Deltas, suppressions et rejeu

Une exécution sortante prend une **portée** (`scope=` dans l'API, la liste *Enregistrements* du service,
le paramètre `scope` du bean de workflow et de la tâche planifiée) :

| Portée | Enregistrements envoyés |
|---|---|
| `all` (défaut) | tous ceux de la requête, comme avant |
| `unsent` | ceux jamais envoyés avec succès par ce mapping |
| `changed` | ceux dont le document sortant diffère du dernier envoyé (empreinte), les échecs que la politique de nouvel essai autorise, et les suppressions |
| `failed` | ceux dont le dernier envoi a échoué et que la politique autorise à nouveau |
| `dataspaceDelta` | ceux créés, modifiés ou supprimés dans l'espace de données **depuis son snapshot initial** (API de comparaison d'EBX) ; l'espace doit être un espace enfant |

La table **`OutboundState`** (une ligne par mapping et enregistrement) mémorise chaque envoi : statut
`SENT` (avec l'empreinte et le dernier document), `FAILED` (avec l'erreur, le nombre de tentatives et la
date de la dernière) ou `DELETED`. Seuls les envois réussis mettent l'empreinte à jour : un
enregistrement en échec est donc naturellement renvoyé par `changed`, et une modification faite par un
enrichissement entrant aussi.

**Les suppressions** sont propagées quand le mapping déclare une **`deleteOperation`** : l'opération du
connecteur appelée pour un enregistrement disparu (`changed` : présent dans `OutboundState`, absent de la
table ; `dataspaceDelta` : présent dans le snapshot seulement). L'appel reçoit les entrées du mapping
résolues sur l'enregistrement du snapshot quand il existe, plus `key` (la clé primaire) et les champs
scalaires du dernier document envoyé. Sans `deleteOperation`, les suppressions sont ignorées.

**Politique de nouvel essai**, par mapping : `maxAttempts` (5) et `retryDelayMinutes` (30). Un
enregistrement `FAILED` est retenté par `changed` et `failed` tant que les tentatives ne sont pas
épuisées ; tant que le délai n'est pas écoulé il est compté « en attente » ; ensuite il reste `FAILED`
jusqu'à un rejeu manuel.

**Simulation.** Avec `dryRun=true`, une exécution sortante construit chaque document et le montre (`changes`) sans appeler le service ; l'état des envois n'est pas touché.

**Exécutions et rejeu.** Chaque exécution (un appel REST, une étape de workflow, une exécution planifiée
ou du service) reçoit un **`runId`**, écrit sur chaque ligne du journal et renvoyé par l'API et le bean de
workflow. `POST /ebx-connectors/rest/v1/replay/{runId}` rejoue les appels de cette exécution terminés
en `ERROR`, entrants ou sortants, sur la version actuelle des enregistrements (un enregistrement
disparu est ignoré, ou supprimé à distance si le mapping a une `deleteOperation`). `GET /runs/{runId}`
liste les appels d'une exécution et dit si elle est rejouable ; `GET /outbound?mapping=&status=FAILED`
liste les enregistrements à rejouer d'un mapping (`status=SENT|DELETED|ALL` pour le reste).

```bash
# envoyer ce qui a changé depuis le dernier envoi, suppressions comprises, 500 enregistrements au plus
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/7?scope=changed&max=500"
# le delta d'un espace de données enfant, en simulation
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/7?scope=dataspaceDelta&dryRun=true"
# rejouer les échecs d'une exécution
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/replay/R20260906-231500-0007"
```

### 10.2 Publier un produit de données entier

Un consommateur d'un produit de données ne doit jamais le reconstruire à partir des tables membres,
delta après delta. Là où le § 10.1 envoie les enregistrements d'un mapping au fil du temps, **publier
un produit** (`core.ProductPublisher`, MCP `connectors_publish`, `POST /publish`, bean de workflow
`ProductPublishTaskBean`) envoie **un ou plusieurs mappings sortants racines, tous sur le même
connecteur**, comme un tout, selon un **contrat** (les colonnes exposées), avec un seul instant de
coupure et **un commit atomique pour toutes les tables**. Périmètre v1 : **Snowflake uniquement**,
**rechargement complet uniquement** (chaque table cible est entièrement remplacée à chaque publication,
il n'y a pas de mode incrémental/delta ici — c'est le rôle du § 10.1), un produit = une table Snowflake
par mapping racine. Les **enfants** actifs d'un mapping racine (sous-objets) ne sont pas publiés comme
des tables séparées : chacun devient une colonne `VARIANT` (JSON) de la table de son parent, contenant
le tableau des objets enfants exactement comme `MappingEngine.buildOutbound` le construit déjà pour un
envoi sortant ordinaire.

**L'entrée `table` du mapping est obligatoire** (elle n'est pas nécessaire pour un envoi sortant
ordinaire) : elle nomme la table cible, mise en majuscules et normalisée en `[A-Z_][A-Z0-9_]*`. Elle
peut être **qualifiée par le schéma** (`DB.SCHEMA.TABLE` ou `SCHEMA.TABLE`) — seul le dernier segment
séparé par un point sert de nom de table (normalisé) — mais donner le nom pleinement qualifié permet à
la **lineage déclarée** du mapping (`OpenLineageEmitter`/`connectors_lineage_declare`, § 3.7) de
pointer vers le **même** asset du catalogue que celui créé par une exécution de publication, au lieu
d'un second asset, parasite, sous le schéma par défaut du connecteur (`OpenLineageEmitter.datasetOf` ne
préfixe une entrée `table` que lorsqu'elle n'est *pas* qualifiée). Si l'entrée qualifiée nomme un
schéma différent du champ `schema` de la requête de publication, la publication échoue avec une erreur
nommant les deux — il n'y a jamais de redirection silencieuse vers le schéma de l'entrée. Chaque règle
et chaque enfant actif du mapping devient une colonne : une règle scalaire (cible sans `.` ni `[]`)
devient une colonne nommée d'après la cible ; une règle visant un chemin imbriqué devient une colonne
`JSON` unique nommée d'après son premier segment de chemin (plusieurs règles sous le même segment
fusionnent dans cette même colonne) ; un mapping enfant devient une colonne `JSON` nommée d'après son
`collectionPath`. Quatre colonnes techniques sont ajoutées à chaque table : `_EBX_KEY` (la clé primaire
de l'enregistrement, formatée — la clé métier quand la table en a une, `key=true` sinon), `_RUN_ID`,
`_PUBLISHED_AT`, `_CONTRACT_VERSION`.

**Enregistrements pivots (golden records) et table de correspondance (crosswalk).** Sur une table sous
Match and Merge (`ebx-addon-mame`, configuré indépendamment de ce module), le perdant d'une fusion
(état `MERGED`) et les enregistrements logiquement supprimés (état `DELETED`) seraient sinon publiés à
côté des enregistrements pivots, sans rien qui indique à un consommateur à quel enregistrement pivot
appartient la clé d'une transaction donnée. L'entrée de mapping **`mergedRecords`** (`exclude`, par
défaut, ou `include` ; toute autre valeur fait échouer la publication avant toute écriture) contrôle
cela par table : `exclude` laisse de côté les enregistrements `MERGED`/`DELETED` — les enregistrements
écartés ne comptent jamais dans `max` — `include` garde tout. Mettre l'entrée de mapping **`crosswalk`**
à `true` fait passer le défaut à `include` (une table de clés/de correspondance est faite pour porter
les enregistrements fusionnés, résolus). Deux transformations vont avec `crosswalk` :
**`golden(chemin[,dataspace,dataset])`** prend la clé primaire formatée d'un enregistrement et la
résout à la clé de son **enregistrement pivot** quand l'enregistrement est `MERGED` (inchangée si
l'enregistrement est introuvable, si la table n'est pas sous Match and Merge, ou si son état n'est pas
`MERGED`) ; **`mdmState(chemin[,dataspace,dataset])`** renvoie l'état de consolidation de cet
enregistrement. Une publication qui exclut les enregistrements fusionnés les compte quand même dans le
rapport : `tables[].skipped.merged` / `.deleted`, et le `message` d'une exécution réussie mentionne le
total quand il est supérieur à zéro. Forme typique — une table de clés publiant chaque enregistrement
(fusionnés compris) résolu vers son enregistrement pivot, et une table de correspondance par système
source repointée de la même façon :

```
# table de clés : chaque enregistrement, fusionnés compris, résolu vers son enregistrement pivot
mapping racine sur /root/Companies, entrées {table: COMPANY_KEYS, crosswalk: true}
  ./Identification/Id -> ID
  ./Identification/Id -> GOLDEN_ID   golden('/root/Companies')
  ./Identification/Id -> MDM_STATE   mdmState('/root/Companies')
# correspondance système source, repointée vers l'enregistrement pivot
mapping racine sur /root/CrossRefCompanies, entrées {table: COMPANY_XREF}
  ./source          -> SOURCE_SYSTEM
  ./pkRecordSource  -> SOURCE_KEY
  ./company         -> COMPANY_ID    golden('/root/Companies')
  ./company         -> ORIGINAL_COMPANY_ID
```

Un consommateur ne joint plus jamais sur une clé fusionnée (morte) : `JOIN COMPANY_XREF x ON
x.SOURCE_SYSTEM='ERP' AND x.SOURCE_KEY=t.CUSTOMER_ID JOIN COMPANY_360 c ON c.ID=x.COMPANY_ID`.
`COMPANY_KEYS`/`COMPANY_XREF` publient légitimement une seconde fois la table mise en correspondance
(la table de clés ne compte pas comme couvrant cette table, du point de vue de la couverture de port du
catalogue de données).

La table **`Publication`** (espace/jeu de données `Connectors`, `/root/Publication`) garde une ligne par
appel de `connectors_publish`, simulations comprises : `runId` (sa clé primaire), `product`,
`contractVersion`, `connector`, `schema`, `status` (`OK`, `ERROR`, `DRY_RUN`), `message`, `cutoff`,
`startedAt`, `endedAt`, `rowCount`, `mappings` (CSV des ids publiés), `tables` (CSV de `TABLE=lignes`)
et `report` — le rapport complet ci-dessous, en JSON (`osd:text`, tronqué à 100000 caractères). C'est la
trace durable d'une publication ; un appelant qui n'a récupéré qu'un `runId` (par ex. le bean de
workflow, dont la sortie `message` est tronquée à 180 caractères comme toute variable de workflow,
§ 12.3) relit le rapport complet dans cette ligne.

Le **rapport** (renvoyé par chaque surface, et stocké tel quel dans `Publication.report`) :

```json
{"runId":"R20260919-143000-0001","status":"OK|ERROR|DRY_RUN","message":"…","product":"COMPANY_360","contractVersion":"1",
 "connector":"Snowflake","schema":"SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS","cutoff":"2026-09-19T14:30:00","startedAt":"…","endedAt":"…",
 "durationMs":12345,"rowCount":632,"truncated":false,
 "tables":[{"mapping":210,"label":"Company 360 -> Snowflake","ebxTable":"Companies/Companies/root/Companies","table":"COMPANY_360",
            "action":"CREATED|EXISTS|RECREATED|PLANNED","rows":632,"batches":2,
            "skipped":{"merged":5,"deleted":1},
            "columns":[{"name":"ID","kind":"INTEGER","type":"NUMBER(38,0)","source":"./Identification/Id","key":true},
                       {"name":"ADDRESSES","kind":"JSON","type":"VARIANT","source":"addresses[]","key":false}],
            "sample":[{"ID":1,"NAME":"…"}]}],
 "plan":["CREATE SCHEMA IF NOT EXISTS …","…"],
 "errors":[]}
```

`sample` (les 3 premiers documents par table) et `plan` (les instructions qui seraient exécutées) ne
sont présents qu'en simulation (`dryRun=true` : rien n'est exécuté, tout le reste du rapport —
dérivation des tables/colonnes, comptes de lignes, `truncated` — est calculé exactement comme une
exécution réelle). `max` borne les lignes lues par table (défaut 10000, plafond 200000) ; l'atteindre
met `truncated=true` et le message le signale, le statut reste `OK` par ailleurs. `recreate=true`
permet de remplacer une table cible dont les colonnes diffèrent du contrat (`CREATE OR REPLACE TABLE`)
au lieu de faire échouer la publication avec les noms des colonnes manquantes/en trop. L'objet
`skipped` d'une table n'est présent que si `mergedRecords=exclude` (le défaut) a effectivement écarté
des enregistrements (`merged` et/ou `deleted` supérieur à zéro) ; le `message` global d'une exécution
réussie (réelle ou simulée) mentionne le total sur toutes les tables quand il est positif.

**Le commit atomique et ses limites.** `prepare` crée le schéma, la table de contrôle
(`EBX_PUBLICATION`) et, par table cible, la table elle-même et une table de préchargement vide ; `load`
remplit chaque table de préchargement par lots (propriété du connecteur `publish.batchSize`, défaut
500) ; **`commit`** est une seule requête exécutant `BEGIN` … une paire `DELETE`+`INSERT … SELECT *`
par table cible (depuis sa table de préchargement) … une insertion dans `EBX_PUBLICATION` … `COMMIT` :
soit toutes les tables arrivent ensemble, soit aucune. `EBX_PUBLICATION.PUBLISHED_AT` est écrit avec
`SYSDATE()`, toujours en **UTC**, dans une colonne `TIMESTAMP_NTZ` — jamais `CURRENT_TIMESTAMP()`, qui
stocke le fuseau horaire propre à la session Snowflake sans conserver le décalage, et se relit donc
silencieusement comme s'il s'agissait d'UTC (par ex. 09h06 à Los Angeles pour une coupure à 18h06
CEST). `cleanup` (tables de préchargement supprimées)
s'exécute après un commit réussi et après tout échec, au mieux, sans jamais masquer le résultat. C'est
volontairement étroit, et les points d'extension restent visibles plutôt que cachés : **rechargement
complet uniquement**, pas de publication incrémentale/upsert d'un produit (les deltas du § 10.1 restent
le moyen de garder un seul mapping continuellement synchronisé) ; **pas d'isolation de snapshot** de la
lecture source (les enregistrements sont lus table par table, pas contre un instantané cohérent unique
de l'espace de données EBX, donc une écriture concurrente pendant une longue publication peut se
refléter dans la coupure d'une table et pas d'une autre) ; **Snowflake uniquement** (`TablePublisher`,
§ 3.3.1, est le point d'extension pour une seconde plateforme) ; aucun GRANT ni sémantique de snapshot
d'espace de données n'est géré par cette fonctionnalité.

**Décrire et référencer les tables publiées.** Après le commit, et au mieux (un refus de Snowflake est une
entrée `warnings` du rapport, jamais une erreur sur une publication validée), le publieur pose un
commentaire sur chaque table et colonne et les tags `EBX_PRODUCT`, `EBX_CONTRACT_VERSION`,
`EBX_SOURCE_TABLE` et, si la requête les porte, `EBX_OWNER` et `EBX_DOMAIN` (champs `description`,
`owner`, `domain`, `title`). Avec la propriété du connecteur **`listing.enabled`** à `true`, il partage aussi
le schéma (`EBX_<PRODUIT>_SHARE`) et crée, ou met à jour en place, un listing organisationnel
`EBX_<PRODUIT>` visible de tous les comptes de l'organisation dans la Marketplace interne. Cela demande
`CREATE SHARE` et `CREATE ORGANIZATION LISTING` sur le compte et une base dont le rôle du connecteur est
propriétaire. Propriétés : `listing.enabled` (défaut `false`), `listing.supportContact` (une adresse e-mail,
obligatoire si le listing est activé) et `listing.region` (facultative, défaut la région du compte).

**Le sens de chaque colonne, et où le trouver dans Snowflake.** Quand une colonne porte un sens (libellé et
description du modèle EBX, nom métier, terme du glossaire, définition et synonymes du catalogue), son
commentaire met ce sens en premier (`Customer name: … | Also called: … | Glossary term: … | ON EBX field ./name`)
et la colonne reçoit les tags `EBX_GLOSSARY_TERM`, `EBX_SENSITIVITY` et `EBX_CLASSIFICATION`. Le publieur écrit
aussi, dans le schéma de publication, une **semantic view** `EBX_<PRODUIT>_SEMANTIC`
(`CREATE OR REPLACE SEMANTIC VIEW`, remplacée à chaque publication) : une table logique par table publiée, une
dimension par colonne métier avec ses synonymes du glossaire (`WITH SYNONYMS`) et son sens en commentaire (nom
métier et définition seulement), les colonnes clés en `PRIMARY KEY`, une métrique `RECORD_COUNT` par table, pas de relation. C'est ce que lit Cortex Analyst pour répondre en mots métier.
Propriété `semanticView.enabled` (défaut `true`) ; il faut `CREATE SEMANTIC VIEW` sur le schéma. Dans Snowsight :
commentaires dans l'onglet *Columns* de la table, tags par
`TAG_REFERENCES_ALL_COLUMNS('<DB>.<SCHEMA>.<TABLE>', 'table')`, la vue sous *Semantic views* du schéma
(`SHOW SEMANTIC VIEWS IN SCHEMA`, `DESCRIBE SEMANTIC VIEW`). Les tables scannées par le catalogue sont
seulement lues : rien n'y est jamais réécrit.

```bash
# simulation : plan + un échantillon de 3 lignes par table, rien n'est exécuté
curl -u admin:admin -X POST http://localhost:8080/ebx-connectors/rest/v1/publish -H "Content-Type: application/json" \
     -d '{"mappings":[210],"schema":"SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS","product":"COMPANY_360","dryRun":true}'
# exécution réelle, plusieurs mappings racines du même connecteur, en remplaçant une table dont le contrat a changé
curl -u admin:admin -X POST http://localhost:8080/ebx-connectors/rest/v1/publish -H "Content-Type: application/json" \
     -d '{"mappings":["Company 360 -> Snowflake"],"schema":"SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS","product":"COMPANY_360","contractVersion":"1","recreate":true}'
```

## 11. Concepteur graphique

Sur une ligne de `Mapping` : bouton du formulaire, ou action **Concevoir le mapping** de la barre.

- **Barre** : sens, racine de la réponse, `+ Valeur constante`, masquer les champs non mappés, compteur,
  vue Diagramme / Tableau, zoom (− +, Ctrl+molette), plein écran (Échap pour sortir) ; la zone se
  redimensionne par sa poignée.
- **Appel du service** : les entrées, leur expression, un sélecteur de champ EBX, des puces de
  transformation. Repliable.
- **Entrée** (gauche) et **Sortie** (droite) : arbres hiérarchiques, badges de type (STR, NUM, DATE,
  BOOL, FK, LIST, OBJ), valeurs d'exemple en gris, étoile sur la clé primaire, recherche, tout déplier /
  replier. En sens entrant, l'entrée est la réponse du service et la sortie la table EBX ; en sortant,
  l'inverse.
- **Liens** : glisser le port d'un champ d'entrée sur un champ de sortie (ou cliquer l'un puis l'autre).
  Un lien avec transformations devient un **nœud** ; une constante, un nœud sans entrée. Déposer sur
  un champ déjà mappé re-pointe son lien.
- **Inspecteur** (sous le diagramme) : source → étapes (puces éditables, ajout depuis la liste,
  retrait) → cible ; pipeline en texte, politique, commentaire, suppression.
- **Boutons EBX** : *Enregistrer les règles* (règles + entrées + sens + racine), *Tester sur l'exemple*
  (simulation sur l'enregistrement indiqué, ou le premier de la table), *Charger les champs externes*
  (appelle vraiment le service pour l'exemple et remplit l'arbre avec les valeurs), *Créer le mapping
  inverse*, *Créer un mapping de sous-objet*, *Créer le mapping de réponse*.

**Clavier et lecteur d'écran.** Le concepteur s'utilise sans souris. Les arbres sont des widgets `tree` avec un seul
arrêt dans l'ordre de tabulation : les flèches se déplacent entre les champs, Droite et Gauche ouvrent et ferment un
groupe (Gauche sur un champ va à son groupe), Début et Fin vont aux extrémités. Sur un champ d'entrée, **Entrée** ou
**Espace** le choisit (une région vocale polie l'annonce) ; sur un champ de sortie, la même touche crée le lien, ou
sélectionne le lien qu'il porte déjà (le bouton *Valeur constante* se comporte de même). **Suppr** sur un champ de
sortie lié demande, dans un dialogue intégré à la page, s'il faut retirer le lien. Dans le diagramme, chaque lien a un
seul arrêt (son nœud s'il en a un) : les flèches se déplacent entre les liens, Entrée en sélectionne un, Suppr le retire
après confirmation ; les lignes de la vue Tableau fonctionnent de même. Chaque action est un vrai bouton ou un champ nommé
(plus d'ancres réservées à la souris), les bascules disent si elles sont enfoncées, et ce que la couleur disait seule est
aussi écrit : un champ lié porte une coche et « lié » dans son nom lu, le lien sélectionné un contour plus épais, la vue
active et la constante armée une coche. Changer le sens et ajouter un paramètre de service passent par un dialogue intégré
(Échap annule, Tab reste dedans, le focus revient d'où il vient) au lieu des boîtes du navigateur. Le glisser-déposer
continue de fonctionner à la souris.

## 12. Exécuter

### 12.1 Service utilisateur (interface)

Sur la vue tabulaire d'une table ayant des mappings : **Synchroniser avec une application externe**.
Options : mapping, portée (sélection, vue, toute la table), maximum, simulation. Le rapport détaille
chaque enregistrement.

Ce que l'écran montre autour d'une exécution :

- **avant** : ouvert depuis un lien portant des entrées d'exécution, un bandeau jaune dit que rien n'a
  encore été exécuté ;
- **pendant** : un clic sur **Exécuter** fait apparaître une barre animée « Exécution en cours… »
  (une simulation, et une exécution sur les enregistrements d'une table, sont synchrones ; c'est la barre de la
  recherche fédérée Matériel, pas une modale, et une minuterie de 2 minutes la retire si EBX refuse le formulaire).
  Un **flux exécuté pour de vrai** est différent : il démarre sur un fil d'arrière-plan (ci-dessous) et l'écran répond
  aussitôt avec le panneau d'exécution en direct ;
- **après** : un bandeau en haut, « Exécution terminée à HH:MM:SS » (ou « Simulation terminée »), avec
  les comptes — vert, rouge si un élément a échoué, bleu pour une simulation ;
- **Retour** : ouvert en page ebx-ui pleine (les liens du catalogue), EBX n'affiche pas de bouton Fermer ;
  **Retour** revient à la page où le lien a été cliqué (le catalogue la garde dans `sessionStorage`,
  sous une clé qui contient votre identifiant EBX), sinon d'un cran dans l'historique. Il se masque là où EBX dessine son propre Fermer.

**Mode d'exécution, confirmation et détail.** L'écran s'ouvre sur un choix *Mode d'exécution*, sous les
tuiles du flux : deux cartes, **Simuler** (bleu, rien n'est écrit ni envoyé) et **Exécuter pour de vrai**
(ambre), utilisables au clavier comme un groupe de boutons radio ; la simulation reste le choix par défaut.
Le bouton du bas suit le mode (*Simuler* / *Exécuter et écrire*, ambre en exécution réelle). Le mode passe
dans le paramètre de requête caché `ftcsMode`. Après une simulation, un bandeau donne le nombre de
modifications prévues et un bouton **Appliquer ces modifications** qui relance les mêmes options pour de
vrai. Une exécution réelle sur **toute la table** d'un mapping d'enregistrements ne part pas tout de suite :
un encadré donne le nombre d'enregistrements (et le maximum s'il est plus bas) avec **Confirmer**,
**Simuler d'abord** et **Annuler**. Le contrôle est fait sur le serveur (paramètre `ftcsConfirm` =
`<idMapping>|ALL`), donc un autre mapping choisi ensuite redemande la confirmation. Les tuiles comptent les
modifications simulées (`DRY_RUN`) comme *Modifications prévues*. Le détail affiche les erreurs en premier et
seulement 20 lignes. Les 20 suivantes viennent du serveur par un appel Ajax EBX, sur **Afficher 20 de plus**
ou quand ce bouton arrive à l'écran. Les filtres par statut (erreurs, mis à jour, inchangés, sans données,
ignorés) sont appliqués sur le serveur. Une page demandée par l'écran d'une exécution plus ancienne est
refusée (« relancez l'exécution »). Le bandeau de progression affiche le temps écoulé.

**Présentation guidée.** Quand le service s'ouvre avec des entrées d'exécution sur un flux d'import (le
bouton **Reprofiler cette table…** du catalogue), il s'en tient à l'essentiel : le titre nomme le flux et
les entrées (« Catalog profile: EBX_TEST · Companies/Companies/root/Companies »), une carte dit quel
connecteur et quelle opération s'exécutent, dans quelle table, pour quelles entrées ; seules les cartes du mode
d'exécution sont visibles ; le mapping et le maximum sont repliés sous *Options avancées* (la portée, qu'un
flux ignore, n'est pas affichée) ; le maximum est prérempli à 499 ; la liste des éléments est repliée sous
*Détail (N éléments)*, ouverte seulement si un élément a échoué, avec des noms courts (`Identification/Id`
plutôt que le prédicat de clé) et des statuts traduits. Ouvert depuis la barre d'outils d'une table,
l'écran garde sa présentation complète.

**Quand une exécution échoue.** L'écran ne montre pas l'exception brute. Pour les échecs fréquents il dit ce qui
s'est passé et quoi faire ensuite, dans la langue de la session EBX de l'utilisateur, puis replie le détail
technique (le message expurgé, jamais une trace de pile) sous *Détail technique* : connexion refusée, hôte qui ne
se résout pas, délai dépassé, identifiants ou droits refusés, limite de débit, adresse privée ou non autorisée (avec
la propriété qu'un administrateur pose : `ebx.connectors.egress.privateExceptions`, `ebx.connectors.allowedHosts`),
secret stocké en clair (`ebx.connectors.secrets.allowClear`), mapping déjà en cours, trop d'exécutions en arrière-plan,
mapping invalide. Tout autre échec dit « L'opération a échoué » et montre le détail technique ouvert ; la trace complète
est dans le journal du noyau. La même cause et la même action apparaissent sous une ligne en échec du détail et dans le
résultat de *Tester la connexion* (`core/ErrorCatalog` classe ; les phrases sont les clés `error.*` des bundles de
l'interface, du texte fixe : un secret ou une valeur d'enregistrement cités par le système distant n'y arrivent jamais).

Un flux exécuté depuis l'écran est une exécution explicite, comme sur `POST /sync` : sa lineage part en un
seul événement OpenLineage à la fin. Sans cela, chaque élément importé envoyait son propre événement et le
catalogue les importait un par un (un profil de 58 colonnes prenait 30 s à l'écran contre 4,6 s en REST,
le 2026-09-14).

Trois paramètres d'URL facultatifs, déclarés comme paramètres d'entrée du service, le préremplissent :
`mapping=<id>` (le mapping choisi), `inputs=<nom=valeur;…>` (entrées d'exécution d'un flux, § 5, affichées
au-dessus du rapport) et `dryRun=false` (ouvre une fois sur *Exécuter pour de vrai* ; l'exécution demande
toujours un clic sur **Exécuter**). Le bouton **Reprofiler cette table** du catalogue de données ouvre le
service ainsi sur son flux de profilage.

**Comment les passer.** Les routes ebx-ui (`/ebx-ui/ui/advanced/…`, `/ebx-ui/ui-node/…`) ignorent les
paramètres de requête simples d'un service : `…?service=ebx-connectors@ConnectorSync&serviceMode=TABLE_VIEW&mapping=159`
ouvre le mapping par défaut (mesuré le 2026-09-14). Deux formes marchent :

- une URL de composant web, qu'EBX redirige :
  `/ebx/?branch=<dataspace>&instance=<dataset>&xpath=<chemin de table>&service=ebx-connectors@ConnectorSync&mapping=123&inputs=tables%3DSALES.ORDERS&dryRun=false` ;
- la route ebx-ui avec `serviceParams`, le base64 d'un objet JSON (ce que construit le bouton du catalogue) :
  `/ebx-ui/ui-node/data/B<dataspace>/<dataset><chemin de table>?service=ebx-connectors@ConnectorSync&serviceMode=TABLE_VIEW&serviceParams=<base64 de {"mapping":"123","inputs":"tables=SALES.ORDERS","dryRun":"false"}>`.

Les préréglages sont lus à l'ouverture du service ; EBX réécrit ensuite l'adresse en ne gardant que
`mapping` : un rechargement de la page perd les entrées d'exécution, et le bandeau qui les liste disparaît avec elles.
Le maximum compte les éléments importés (un par colonne pour un profil du catalogue). Il n'a pas de borne haute sur
le formulaire : un flux exécuté pour de vrai démarre en arrière-plan et n'en a pas ; une simulation, ou une exécution sur des
enregistrements, garde ses éléments dans la requête HTTP et est refusée au-delà de 4 999 avec un message qui dit pourquoi.
Chaque flux démarre à 499.

**Panneau d'exécution en direct (flux exécutés pour de vrai).** **Exécuter et écrire** sur un flux entrant ne s'exécute plus
dans la requête HTTP. L'écran démarre l'exécution par le même chemin que `connectors_sync(async=true)` et
`POST /sync?async=true`, donc avec les mêmes limites d'admission (`-Debx.connectors.import.async.maxConcurrent` et
`maxQueued`, une exécution à la fois par mapping), et répond aussitôt avec un panneau **Exécution en arrière-plan** sous la
zone de résultat :

- l'état en toutes lettres (*En cours*, *Terminée*, *Terminée avec erreurs*, *En échec*, *Arrêtée*, *Interrompue*), une barre
  animée tant qu'elle tourne, et des tuiles pour les éléments traités, créés, mis à jour, inchangés, ignorés, les erreurs et la
  durée (les compteurs sont ceux du dernier lot enregistré, lus dans le même état d'exécution que `connectors_run_get` ; le
  panneau interroge le serveur toutes les deux secondes et s'arrête quand l'exécution se termine) ;
- **Arrêter** (droit RUN) demande à l'exécution de s'arrêter à la fin de son lot en cours ; elle garde sa position ;
- **Reprendre à l'élément N** apparaît pour une exécution arrêtée, interrompue ou en échec qui a enregistré une position,
  quand l'écran l'a lancée lui-même : il démarre une nouvelle exécution en arrière-plan à cet élément (la source doit renvoyer
  ses éléments dans le même ordre qu'avant, comme pour `resumeFrom` en MCP et REST) ;
- **Actualiser**, et des liens vers les lignes du journal de l'exécution et vers ses échecs ;
- un échec est dit en cause et action suivante (voir plus haut), et une exécution coupée à son maximum dit combien
  d'éléments la source avait.

On peut quitter l'écran : l'exécution continue. Rouvrir l'écran sur un mapping dont le flux tourne déjà (lancé depuis
n'importe quelle entrée de ce nœud) montre le même panneau, avec Arrêter. Un lecteur qui n'a que le droit READ regarde
seulement ; **Arrêter** et **Reprendre** demandent RUN, que l'écran exige déjà. Le formulaire d'une ligne `ExecutionRun`
montre le même panneau, avec Actualiser et Arrêter mais sans Reprendre.

### 12.2 API REST du module

Base `http://<hôte>/ebx-connectors/rest/v1`, authentification EBX (Basic, jeton, session) ; la session
de l'appelant exécute les mappings ; `?dryRun=true` partout.

```bash
# catalogue et mappings
curl -u admin:admin http://localhost:8080/ebx-connectors/rest/v1/connectors
curl -u admin:admin "http://localhost:8080/ebx-connectors/rest/v1/mappings?table=/root/Companies"

# un mapping sur un enregistrement (id ou libellé ; ?predicate= au lieu de key)
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/32?key=511"

# un flux (sans key) : tous les éléments qu'il renvoie ; avec des entrées d'exécution lues par le mapping en ${param.nom} (§ 5)
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/123?max=10000"
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/sync/123?max=10000&inputs=tables%3DSALES.ORDERS"

# un enchaînement ordonné (ids/libellés), ou par connecteur / connecteur/opération
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/enrich?dataset=Companies&table=/root/Companies&key=512&mappings=37,38,1,42"
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/enrich?dataset=Companies&table=/root/Companies&key=511&connectors=GLEIF/searchByRegistration,GLEIF/lei,VIES,BAN"

# webhook : import d'un document par un mapping de flux (JSON, ndjson, csv ; GET = contrôle de santé)
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/webhook/34?dryRun=true" \
     -H "Content-Type: application/json" -d '{"items":[{"siren":"327733184","name":"Microsoft France","city":"Issy-les-Moulineaux","country":"FR"}]}'

# conflits de la politique TRUSTED : liste (statut OPEN par défaut), puis décision du steward
curl -u admin:admin "http://localhost:8080/ebx-connectors/rest/v1/conflicts?dataset=Companies&table=/root/Companies"
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/conflicts/2/accept?comment=Nom%20officiel%20INSEE"
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/conflicts/3/reject"

# un connecteur depuis une spécification OpenAPI (JSON ou YAML, corps ou url)
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/connectors/openapi?id=RechercheEntreprises&url=https://recherche-entreprises.api.gouv.fr/openapi.json"

# publier un produit de données entier (§ 10.2) ; 200 pour OK/DRY_RUN, 422 pour ERROR
curl -u admin:admin -X POST http://localhost:8080/ebx-connectors/rest/v1/publish -H "Content-Type: application/json" \
     -d '{"mappings":[210],"schema":"SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS","product":"COMPANY_360","dryRun":true}'
```

Réponse (extrait) :

```json
{"record":"512 TIBCO Software Inc.","dryRun":false,"ok":3,"errors":0,"conflicts":0,"hasConflicts":false,
 "results":[{"mapping":37,"connector":"Wikidata","operation":"search","status":"OK","message":"2 change(s), 0 skipped",
             "durationMs":433,"changes":["./Governance/wikidataId:  -> Q513861"]}, ...]}
```

Le webhook exige un compte EBX (Basic ou jeton) avec le droit RUN ; l'import tourne sous ce compte.

### 12.3 Workflow

Bean `com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean` (module `ebx-connectors`), ou le modèle
prêt à l'emploi `ConnectorEnrichment`. Entrées : `dataspace`, `dataset`, `xpath` (un enregistrement) ou
`tablePath` + `predicate` + `maxRecords` (une table), `mappings` (liste ordonnée, forme recommandée)
ou `connectors` ou `connector`/`operation`/`direction`, `dryRun`, `failOnError`, `inputs` (entrées d'exécution des flux parmi les mappings, § 5). Sorties : `report`,
`changedCount`, `okCount`, `errorCount`, `status` (OK, PARTIAL, ERROR, NO_DATA), `conflictCount`, `hasConflicts`, `runId` (entrée `scope` pour les deltas sortants, § 10.1)
(`true`/`false`, pour router vers la tâche utilisateur « Gérer les conflits des connecteurs », § 4.5), `truncated`, `problems` et
`warnings` (les entrées d'exécution qu'aucun mapping de l'étape n'a lues, § 5 ; vide s'il n'y en a pas).
L'étape n'échoue pas par défaut (`failOnError=false`) : le workflow route sur les sorties. `truncated`
vaut `true` quand un flux ou la table contenait plus que `maxRecords`, donc que l'étape n'a pas tout
traité ; `problems` liste ce qui empêche l'exécution d'être un succès complet (`errors`, `rejected`,
`conflicts`, `truncated`, `stopped`, séparés par des virgules) et reste vide quand tout est propre. Un
flux dont l'appel à la source a échoué compte pour une erreur. Exemple de lancement
par MCP : publication `ConnectorEnrichment`, paramètres `xpath=/root/Companies[./Identification/Id=512]`,
`mappings=37,38,1,42,44`.

Dans l'éditeur de workflow, déclarer l'étape comme tâche script de **bibliothèque** et choisir le bean
dans la liste du module `ebx-connectors` (clé
`ebx-connectors@com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean`) : c'est ce qui fait apparaître
ses paramètres d'entrée et de sortie dans le formulaire de l'étape, où chacun est associé à une variable
du contexte de données (`mappings` = `${mappings}`, `xpath` = `${xpath}`...) ; ces variables, déclarées
`in`, sont ensuite demandées au lancement. Une tâche script « spécifique » saisie avec le nom brut de la
classe s'exécute aussi, mais l'éditeur n'affiche alors aucun formulaire de paramètres.

**Publier un produit de données** (§ 10.2) est un bean séparé, déclaré de la même façon : clé
`ebx-connectors@com.on.ebx.connect.ebx.workflow.ProductPublishTaskBean`. Entrées : `mappings` (ids ou
libellés, séparés par des virgules, tous sur le même connecteur), `schema`, `product`,
`contractVersion` (défaut `1`), `dryRun`, `recreate`, `maxRecords` (défaut 0 = toutes les lignes), `failOnError`
(défaut **true**, contrairement au `false` de `ConnectorScriptTaskBean` : une publication est censée
réussir en bloc, l'étape est donc mise en erreur par défaut quand le rapport est `ERROR`). Sorties :
`runId`, `status` (`OK`, `ERROR`, `DRY_RUN`), `message` (tronqué à 180 caractères comme toute variable
de workflow), `rowCount`. Le rapport complet n'est jamais passé comme variable — le relire dans la
table `Publication` par `runId` (§ 10.2).

### 12.4 Tâche planifiée

Classe `com.on.ebx.connect.ebx.scheduler.ConnectorScheduledTask`, paramètres `connector`, `operation`,
`direction`, `dataspace`, `dataset`, `tablePath`, `predicate`, `maxRecords` (100), `dryRun`, `scope`,
`inputs` (entrées d'exécution d'un flux, § 5). Exemple : chaque nuit, `GLEIF` / `lei` sur `/root/Companies`
avec le prédicat `./Identification/LegalEntityIdentifier != '' and ./Governance/gleifLastUpdate = ''`.

Le mapping est trouvé par (`connector`, `operation`, `direction`, `tablePath`) ; si deux mappings
correspondent, la tâche refuse de s'exécuter. Pour un flux, `maxRecords` est le nombre d'**éléments**
importés, pas d'enregistrements de la table : un profil du catalogue de données importe un élément par
colonne ; un profil nocturne d'une source du catalogue prend donc `connector=<son connecteur>`,
`operation=profile`, `dataspace=DataCatalog`, `dataset=DataCatalog`, `tablePath=/root/ProfileSnapshot`,
`maxRecords=10000` (le §3.15 du guide du catalogue liste les autres flux).

**Une exécution planifiée échoue quand son exécution n'est pas un succès complet.** Personne ne regarde
une exécution planifiée : elle est donc marquée en échec dans le planificateur (ce qui a été écrit reste
écrit) quand elle se termine avec des éléments ou des enregistrements en erreur, une source illisible,
des éléments rejetés, des conflits laissés à un data steward, une lecture coupée à `maxRecords`, ou un
arrêt avant la fin. Le message nomme la cause et, pour une lecture coupée, le paramètre à augmenter : la
valeur par défaut de `maxRecords`, 100, est une borne de sécurité, pas une recommandation ; la régler
au-dessus de ce que contient la source. Un enregistrement pour lequel la source n'a pas de donnée n'est
pas un échec, pas plus qu'un enrichissement de table avec prédicat qui atteint `maxRecords` (une
exécution nocturne sur « ce qui n'est pas encore enrichi » vide son stock cent enregistrements à la
fois ; les informations d'exécution disent qu'il en reste). Sans prédicat, la même situation est une
lecture coupée : l'exécution suivante repartirait des mêmes premiers enregistrements. Pour accepter certaines causes sur une tâche donnée, renseigner son paramètre `tolerate`
avec une liste parmi `errors`, `rejected`, `conflicts`, `truncated`, `stopped`, ou `all` (par exemple
`tolerate=conflicts` pour un flux dont les règles TRUSTED laissent régulièrement des propositions à un
data steward) ; une cause tolérée reste écrite dans les informations d'exécution.

### 12.5 Déclencheur de table

Classe `com.on.ebx.connect.ebx.trigger.ConnectorTableTrigger`, déclarée sur une table du modèle :
paramètres `connector`, `operation`, `direction`, `onCreate` (true), `onModify` (false),
`watchedFields` (liste de chemins : ne se déclenche que si l'un change), `blocking` (false : une erreur
du service n'empêche pas l'enregistrement), `publish` (`record` ; `golden` : un enregistrement fusionné envoie
son golden), `skipOrigin` (connexions dont les écritures CDC ne leur sont pas renvoyées). Exemple : à la création
d'une société, `GLEIF` / `searchByRegistration` ; à la modification de `./Identification/LegalEntityIdentifier`,
`GLEIF` / `lei`. Rapprochement en temps réel (CDC en entrée, matching, golden en sortie) :
[docs/REALTIME-MASTERING.md](docs/REALTIME-MASTERING.md).

### 12.6 Depuis un script EBX

Un script EBX ne peut pas appeler le Java : il appelle l'API REST du module par `core.http`, via une
configuration REST externe pointant sur `http://localhost:8080/ebx-connectors/rest/v1` avec un compte
technique (mot de passe saisi dans l'interface).

### 12.7 Outils MCP (agents)

Le module expose ses fonctions en **outils MCP** (Model Context Protocol), pour qu'un agent comme
Claude Code découvre les connecteurs, lance des enrichissements, tranche des conflits ou déplace une
configuration. Les 23 outils : `connectors_help`, `connectors_list`, `connectors_describe`,
`connectors_call`, `connectors_mappings_list`, `connectors_mapping_get`, `connectors_mapping_upsert`, `connectors_mapping_delete`, `connectors_enrich`,
`connectors_sync`, `connectors_replay`, `connectors_run_get`, `connectors_outbound_states`,
`connectors_conflicts_list`, `connectors_conflict_decide`, `connectors_log_search`,
`connectors_config_export`, `connectors_config_import`, `connectors_openapi_import`, `connectors_lineage_declare`,
`connectors_publish`, `connectors_run_stop` (arrêter un import ou un envoi asynchrone en cours) et `connectors_catalog_source` (comment le catalogue de données connaît le système qu'atteint un connecteur). Ils s'exécutent dans la session EBX de l'appelant, les permissions EBX
s'appliquent ; aucun secret ne transite par eux. `connectors_publish` envoie un ou plusieurs mappings
sortants racines comme un produit entier, un commit atomique pour chaque table cible (§ 10.2) ;
`mappings` est une liste d'ids ou de libellés séparés par des virgules, tous sur le même connecteur.

Deux accès, actifs par défaut (`-Debx.connectors.mcp=native|endpoint|both|none`) :

- **Runtime MCP natif d'EBX 6.3** : à l'enregistrement du module, le serveur `ebx-connectors` est
  déclaré par l'API publique (`registerMcpServer` et les annotations `@McpServer` / `@McpTool`, la même
  API que les serveurs workflow et data-access intégrés) et servi par EBX à
  `/ebx-assistant/mcp/v1/ebx-connectors`, avec l'activation et les permissions de l'administration MCP.
  L'appel se fait par réflexion : sur un EBX plus ancien, le runtime est simplement absent, et le log
  du noyau le dit. Dans les builds de développement 6.3 vus jusqu'ici, EBX n'accepte que les modules
  core et add-on ; le log dit alors « EBX declined the MCP server registration » et le point d'entrée
  ci-dessous prend le relais.
- **Le point d'entrée du module**, `POST /ebx-connectors/rest/mcp` : MCP sur streamable HTTP (JSON-RPC
  2.0, réponses JSON, sans server-sent events ; GET et DELETE répondent 405 avec `Allow: POST,OPTIONS`, ce qui fait
  basculer les clients MCP en requête/réponse simple), authentifié comme tout appel REST EBX (HTTP Basic,
  jeton, session). Fonctionne sur toute version d'EBX qui exécute le module. Configuration d'un
  client MCP : `{"type": "http", "url": "http://hote:8080/ebx-connectors/rest/mcp", "headers": {"Authorization": "Basic ..."}}`.

`GET /ebx-connectors/rest/v1/config` et `POST /config?dryRun=true` sont les jumeaux REST des outils
d'export et d'import : connecteurs appariés par id, mappings par connecteur, opération, sens, table et
libellé, règles et entrées remplacées, secrets jamais exportés ni écrasés.

## 13. Journal et statuts

Chaque appel écrit une ligne dans `SyncLog` : horodatage, connecteur, opération, sens, table,
enregistrement, statut, message, durée, origine (`user service`, `workflow admin`, `REST admin`,
`scheduler`, `trigger`, `webhook …`).

| Statut | Sens |
|---|---|
| `OK` | au moins un changement écrit |
| `NO_CHANGE` | appel réussi, rien à écrire (déjà renseigné, politique IF_EMPTY) |
| `NO_DATA` | le service n'a rien renvoyé pour ces entrées (pas de LEI, adresse hors périmètre) |
| `SKIPPED` | une entrée obligatoire est vide sur l'enregistrement, ou aucun mapping applicable |
| `ERROR` | le service ou le mapping a échoué (message = la cause : clé manquante, `MS_MAX_CONCURRENT_REQ`, transformation inconnue…) |
| `DRY_RUN` | simulation : changements calculés, non écrits |

**Métriques de santé.** Une ligne par connexion dans *Santé et métriques des connecteurs*, mise à jour après chaque exécution :
les compteurs sont des **enregistrements**, pas des lignes de journal. Un flux exécuté par lots écrit une ligne `SyncLog` par
lot (1 000 éléments par défaut) plus une ligne propre pour chaque enregistrement en échec ou en conflit ; les métriques lisent le
décompte de la ligne du lot : un lot de 1 000 enregistrements compte 1 000 enregistrements (entrants), ses inchangés et rejetés
comme enregistrements sans changement et ses conflits comme conflits, et les lignes propres de ses enregistrements en échec ne sont
pas comptées une seconde fois. Les percentiles de latence prennent le temps par enregistrement d'un lot. Un échec pour la règle de
*Fiabilité* (un échec d'affilée : dégradé, trois : en échec) est un appel ou un enregistrement en échec, ou un lot dont toutes les
écritures tentées ont échoué ; un lot dont quelques enregistrements ont échoué n'est pas un lot en échec (ses échecs sont dans ses
compteurs, dans l'état de l'exécution et dans leurs lignes de journal). L'alerte d'échecs répétés suit la même règle.

## 14. Recettes complètes

### 14.1 Société française : TVA, adresse, coordonnées (sans clé)

1. Créer la société (SIREN, adresse) ; par exemple par le webhook (§ 12.2).
2. `POST /enrich?...&mappings=30,32` : **VIES** (`vat = ${SIREN|vatfr}`) écrit la raison sociale et un
   identifiant `VAT` ; **BAN** (`address = rue code ville`) écrit latitude et longitude.
3. Avec une clé Sirene : ajouter `3,4` (unité légale, siège) ; avec Google : `5`.

### 14.2 Société étrangère : trouver le LEI puis la fiche

`mappings=37,38,1,42,44` : **Wikidata** cherche l'entité par le nom *sans forme juridique*
(entrée `name` avec `regex`), ses attributs donnent le LEI et l'ISIN, **GLEIF** charge la fiche par ce
LEI (raison sociale, forme, immatriculation, statuts, adresses, identifiant LEI), puis les maisons mères.
L'ordre compte : chaque mapping voit ce que le précédent a écrit.

### 14.3 Normaliser une adresse avec Melissa (aller-retour)

Mapping 35 : entrées `address = ${./Address/Street}`, `locality = ${./Address/City}`,
`postalCode = ${./Address/Zipcode}`, `country = ${./Address/Country}` ; règles `AddressLine1` →
`./Address/Street` (`ALWAYS`), `Locality` → `./Address/City`, `PostalCode` → `./Address/Zipcode`,
`Latitude`/`Longitude` → coordonnées. Seules les adresses vérifiées reviennent (propriété
`verified.only`), d'où `ALWAYS` sans risque. Concaténer deux champs avant l'envoi :
`${./Address/Street} ${./Address/Street2} |> trim`.

### 14.4 Pousser vers Salesforce et garder l'identifiant

Mapping 7 (sortant, `upsert`) construit le compte ; le mapping de réponse (entrant, même connecteur et
opération) écrit `id` → `./Governance/externalId` (ou un identifiant de type `SALESFORCE`). Créer le
jumeau par le bouton du concepteur.

### 14.5 Importer un flux Kafka avec ses sous-objets

Mapping 11 (`consume`, `itemsPath = messages[*].value`, `keyPredicate =
./Identification/LegalEntityIdentifier='${lei}'`, `createIfMissing`), enfants 12 et 13 (`collectionPath
= addresses` / `contacts`, lien `./company`). Lancer par le planificateur ou le service.

### 14.6 Ajouter un service REST sans Java

Créer le connecteur de type `rest` avec ses propriétés `op.*` (§ 3.2), puis un mapping entrant :
entrées = placeholders de `path`/`query`, `responseRoot` si le JSON a une enveloppe, règles depuis
l'arbre chargé par *Charger les champs externes*.

### 14.7 Classification assistée par IA, avec un humain dans la boucle

Le connecteur `Claude` (type `llm`, confiance 30) classe une société d'après sa description :

- entrée `text` = `${./Identification/description}`, entrée `categories` = les sections NACE
  (`A=Agriculture;B=Industries extractives;C=Industrie manufacturière;...` ou une constante tirée d'une
  table de référence) ;
- règles : `category` → `./AdditionalInformation/naceCode` avec `fk(/root/ReferenceData/NaceCode)`,
  politique **TRUSTED** ; `reason` → un champ de commentaire s'il existe.

Le modèle étant la source la moins fiable, il ne remplit que les champs vides ; partout où un registre a
déjà écrit une valeur, sa proposition va dans la table **Conflict** et un steward tranche dans le
gestionnaire de conflits. Le même schéma sert à `extract` (une adresse structurée depuis un texte libre)
et à `match` (l'enregistrement est-il la même entité qu'un doublon candidat ?). La clé d'API se saisit
sur le connecteur ; `provider`, `model`, `temperature` et `language` sont des propriétés.

N'importe quel fournisseur fait le même travail ; une ligne de connecteur est amorcée par fournisseur,
toutes de type `llm` :

| Connecteur | Fournisseur | URL de base | Authentification | `model` par défaut |
| --- | --- | --- | --- | --- |
| `Claude` | `anthropic` | `https://api.anthropic.com` | `BEARER` (envoyé en `x-api-key`) | `claude-sonnet-5` |
| `OpenAI` | `openai` | `https://api.openai.com` | `BEARER` | `gpt-4o-mini` |
| `Gemini` | `google` | `https://generativelanguage.googleapis.com` | `API_KEY_HEADER` `x-goog-api-key` (clé AI Studio) | `gemini-2.5-flash` |
| `AzureOpenAI` | `azure` | `https://<ressource>.openai.azure.com` | `API_KEY_HEADER` `api-key` | le nom du déploiement |
| `Mistral` | `mistral` | `https://api.mistral.ai` | `BEARER` | `mistral-small-latest` |
| `Ollama` | `ollama` | `http://localhost:11434` | `NONE` | `llama3.2` |

Variantes : Vertex AI = fournisseur `google`, URL `https://<région>-aiplatform.googleapis.com`, `OAUTH2`
(compte de service) et propriétés `google.project` / `google.location` ; modèles déployés dans Azure AI
Foundry (Phi, Llama, Mistral sur Azure) = fournisseur `azure-ai`, URL `https://<ressource>.services.ai.azure.com` ;
API v1 d'Azure OpenAI = `azure.apiVersion = v1`. Sans propriété `provider`, l'URL de base décide.
`jsonMode` (auto | true | false) demande à l'API elle-même une réponse JSON pour `extract` / `classify` /
`match` — actif par défaut pour Google, Mistral et Ollama ; `temperature = none` n'envoie pas de
température (modèles de raisonnement). `vision = true` déclare que le modèle accepte les images : alors
seulement le connecteur `documents` lui envoie une image ou un PDF scanné (§ 14.8).

### 14.8 Documents, éléments DAMA et images

Le connecteur `documents` (catégorie IA) transforme des documents non structurés en valeurs d'enregistrement.
Il lit le texte d'un PDF (couche texte), d'un DOCX, d'un texte, Markdown, CSV ou HTML, demande à un connecteur
`llm` (propriété `llm.connector`) les attributs listés dans `fields`, et rend un élément par document. Chaque
valeur porte une confiance et les mots du document qui l'énoncent ; le module vérifie ces mots dans le texte.
Un mapping entrant ordinaire importe l'élément : une valeur au moins égale à `confidence.threshold` (0,8) est
écrite, sa source conservée dans `FieldProvenance.source` ; une valeur en dessous devient une proposition dans
le gestionnaire de conflits. La confiance d'un modèle ne dépasse jamais 0,99 : un seuil de 1 envoie toute valeur à
un gestionnaire de données.

D'où vient le document :

- un dépôt sur la ligne du connecteur, une entrée `content` (base64 avec `name`), ou un fichier du dossier
  serveur `folder` (uniquement sous `-Debx.connectors.documents.roots`) ;
- **un élément DAMA** (EBX Digital Asset Manager) : entrée `record` (l'enregistrement dont le champ média
  porte le document) ou `asset` (son UUID), la table et le champ venant des propriétés `dama.*`. Il est lu
  avec la session de l'appelant : l'enregistrement doit être visible et le champ non masqué, et DAMA applique
  ses propres droits.

Un mapping sur `extract` avec l'entrée `record = ${./id}` remplit les champs de l'enregistrement à partir du
document qu'il porte. On le lance depuis un workflow (`ConnectorScriptTaskBean`) ou depuis
`ConnectorTableTrigger` avec `watchedFields = ./document` : par défaut le trigger s'exécute après
l'enregistrement, en arrière-plan.

L'opération `annotate` (écriture) pose les métadonnées extraites sur l'élément DAMA lui-même
(`metadata = attribut:CODE`, uniquement les types saisis à la main, `IF_EMPTY` par défaut). Elle choisit des
tags (`tags = true` ou `max:N`) **uniquement parmi les tags existants de DAMA**, jamais créés, et les ajoute à
un champ liste de l'enregistrement qui porte le document, désigné par `dama.tags.field` (par exemple
`./documentTags`) : l'API publique de DAMA ne sait pas tagger un élément existant, et le module n'écrit pas
dans les tables internes de DAMA. L'écriture se fait avec la session de l'appelant et demande ce champ en
lecture-écriture. Sans `dama.tags.field`, les tags sont seulement retournés. Sous le seuil, les métadonnées
attendent dans le gestionnaire de conflits en propositions `document (DAMA metadata): CODE`, et les tags en
propositions ordinaires sur le champ des tags ; en accepter une ajoute ce tag à la liste.

**Images et PDF scannés.** Avec `vision = true` sur le connecteur documents **et** sur le connecteur `llm`
(dont le modèle doit accepter les images), une image (png, jpeg, gif, webp, jusqu'à `max.image.bytes`, 5 Mo)
ou un PDF sans couche texte (jusqu'à `max.pages`, 20) est envoyé au modèle lui-même. Rien ne peut vérifier ce
que le modèle lit dans une image : ses valeurs sont plafonnées à `vision.confidence.max` (0,7, sous le seuil),
donc par défaut un gestionnaire les confirme. Les PDF sont lus par les modèles Anthropic, OpenAI, Google et
Mistral ; Azure et Ollama ne prennent que des images. L'image quitte EBX vers le fournisseur : voir § 3.1.2.

Référence : `DOCUMENTS.md`, `DAMA-DOCUMENTS.md` et `DOCUMENTS-VISION.md` dans le dossier `docs` du module.

## 15. Fiche pour un agent d'IA

**Procédure de décision** pour enrichir un enregistrement :

1. `GET /ebx-connectors/rest/v1/connectors` : quels connecteurs ont leurs secrets (`configured`).
2. `GET /ebx-connectors/rest/v1/mappings?table=<table>` : quels mappings entrants existent, actifs,
   non flux, non enfants.
3. Ordonner : d'abord ceux qui **produisent** un identifiant (recherche par SIREN / nom), puis ceux qui
   le **consomment** (fiche par LEI), puis les compléments (adresses, sanctions).
4. `POST /enrich?dataset=&table=&key=&mappings=<liste>&dryRun=true`, lire `results[].status` et
   `changes`, puis relancer sans `dryRun`.
5. `SKIPPED` = entrée vide (compléter la donnée ou choisir un autre mapping) ; `NO_DATA` = le service
   ne connaît pas l'entité ; `ERROR` = lire `message`.
6. `hasConflicts` à true : `GET /conflicts?dataset=&table=&key=` montre ce qu'une source moins fiable a
   proposé ; ne jamais écraser en silence, décider avec `POST /conflicts/{id}/accept|reject` (ou laisser un steward).

**Créer une configuration par Data Services** (base
`http://localhost:8080/ebx-dataservices/rest/data/v1/BConnectors/Connectors/root`, enveloppe
`{"content":{champ:{"content":valeur}}}`, clés étrangères en chaîne) :

```bash
# connecteur + propriété
curl -u admin:admin -X POST $B/Connector -H "Content-Type: application/json" -d '{"content":{"id":{"content":"Wikidata"},"label":{"content":"Wikidata"},"type":{"content":"rest"},"baseUrl":{"content":"https://www.wikidata.org"},"authType":{"content":"NONE"},"active":{"content":true}}}'
curl -u admin:admin -X POST $B/ConnectorProperty -H "Content-Type: application/json" -d '{"content":{"connector":{"content":"Wikidata"},"key":{"content":"op.search.query"},"value":{"content":"action=wbsearchentities&search={name}&language=fr&format=json&limit=1"}}}'
# mapping, entrée, règle (l'id du mapping est attribué par EBX : le relire par son libellé)
curl -u admin:admin -X POST $B/Mapping -H "Content-Type: application/json" -d '{"content":{"connector":{"content":"Wikidata"},"operation":{"content":"search"},"direction":{"content":"INBOUND"},"label":{"content":"Wikidata search by name -> wikidataId"},"dataset":{"content":"Companies"},"tablePath":{"content":"/root/Companies"},"active":{"content":true}}}'
curl -u admin:admin -X POST $B/MappingInput -H "Content-Type: application/json" -d '{"content":{"mapping":{"content":"37"},"name":{"content":"name"},"expression":{"content":"${./Identification/Name}"}}}'
curl -u admin:admin -X POST $B/MappingRule -H "Content-Type: application/json" -d '{"content":{"mapping":{"content":"37"},"position":{"content":10},"sourcePath":{"content":"search[0].id"},"targetPath":{"content":"./Governance/wikidataId"},"transformation":{"content":"trim"},"policy":{"content":"IF_EMPTY"},"active":{"content":true}}}'
```

Scripts prêts : `connectors-module/import_config.py` (réimport idempotent d'un export),
`connectors-module/config_gleif_wikidata.py` (mappings GLEIF/Wikidata supplémentaires, modèle de script
de configuration). Les ids reviennent en **chaînes** par Data Services : comparer en texte.

**Publier un produit de données** (§ 10.2) : d'abord en simulation, lire `tables[].columns` et
`sample` pour confirmer le contrat, puis lancer pour de vrai.

```bash
connectors_publish(mappings="210", schema="SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS", product="COMPANY_360", dryRun=true)
connectors_publish(mappings="210", schema="SNOWFLAKE_LEARNING_DB.DATA_PRODUCTS", product="COMPANY_360")
```

**Invariants** : un mapping enfant ne s'exécute jamais seul, il est enchaîné par son parent ; un flux
ne s'exécute que par import (service, planificateur, webhook), pas par `sync` sur un enregistrement ;
`mappings=` est la seule sélection sans ambiguïté quand un connecteur a plusieurs mappings sur une
table (GLEIF en a deux, dans le mauvais ordre par id).

## 16. Dépannage

| Symptôme | Cause et remède |
|---|---|
| `SKIPPED — Input 'lei' is empty` | l'entrée obligatoire est vide sur l'enregistrement : renseigner la donnée, ou lancer d'abord le mapping qui la produit |
| `set the API key in apiKey` | secret à `TO_BE_SET` : le saisir dans l'interface, sur le connecteur |
| `VIES: MS_MAX_CONCURRENT_REQ` | saturation côté État membre : réessayer plus tard |
| `NO_DATA` sur BAN pour une adresse hors France, ou GLEIF sans LEI | normal, hors périmètre du service |
| `Unknown transformation 'x'` | faute de frappe dans un pipeline ; liste en § 7 |
| `prefix(GLEIF )` donne `GLEIFISSUED` | les espaces de bord d'un argument sont perdus : `prefix('GLEIF ')` |
| aucun champ EBX dans le concepteur | le jeu de données du mapping n'est pas trouvé ou sa table ne se lit pas : vérifier `dataset`, `tablePath`, `dataspace` |
| Wikidata ne trouve pas « X Inc. » | envoyer le nom sans forme juridique (`regex` sur l'entrée `name`) |
| `Dataset Companies not found` | plusieurs jeux de données portent ce nom : préciser `dataspace` |
| le bean de workflow refuse une propriété | classe ou déclaration modifiée : redémarrer Tomcat (EBX charge les beans au démarrage) |

## 17. Catalogue des types de connecteurs

Genres : R = lecture, S = recherche, W = écriture. Les entrées de chaque opération sont listées par
`GET /connectors` et dans le concepteur (panneau Appel du service).

Le catalogue est le contrat d'exécution, pas l'assurance que chaque connecteur est configuré. Un
connecteur est utilisable seulement s'il est **actif**, si son secret ou son identité requis est
configuré, si son pilote optionnel est présent et si sa configuration est valide. Utiliser le catalogue
ou `GET /connectors` pour vérifier la disponibilité, puis **Tester la connexion** ou **Aperçu** avant
une écriture. Un aperçu, `dryRun=true` ou un `peek` non destructif est la preuve à privilégier ;
`receive`, `consume`, `complete` et toute opération W peuvent modifier un système externe ou l'état
d'un message.

Les types de base de données dédiés partagent le moteur JDBC paramétré : utiliser des entrées préparées,
un `max.rows` borné et un DataSource JNDI lorsque l'exploitation gère la connexion. Les types de
messagerie sont volontairement bornés et distinguent **peek**, non destructif, de la consommation ou de
l'acquittement. Les types dépendant d'une bibliothèque optionnelle (Kafka, SFTP, fournisseurs AMQP/JMS,
pilotes JDBC et SDK Azure Service Bus) ne paraissent que si cette bibliothèque est présente ; le reste du
catalogue demeure disponible.

### Procédures stockées via JDBC

La famille JDBC expose l'opération d'écriture `procedure`. Fournir soit `procedure`, un identifiant sûr
composé d'une à trois parties comme `governed.enrich_company`, soit `call`, un unique échappement JDBC tel
que `{call governed.enrich_company(?,?)}`. Un appel explicite n'accepte que l'identifiant et les marqueurs
`?` : littéraux, commentaires, points-virgules et instructions multiples sont refusés. `parameters` est un
tableau JSON (64 éléments au maximum) dont chaque élément porte un `name` unique, un `mode` (`IN`, `OUT`,
`INOUT` ou `RETURN` en première position) et un `type` JDBC borné : `VARCHAR`, `INTEGER`, `BIGINT`, `DECIMAL`,
`BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP` ou `VARBINARY` (les alias documentés sont acceptés). Les valeurs IN
sont lues par nom dans le document sortant, puis dans les entrées de l'opération. Le résultat contient
`affectedRows` ou un unique `result` borné, ainsi que `out` ; chaque valeur OUT est limitée à 64 Kio. Le
connecteur valide une transaction réussie et annule une erreur, mais une procédure peut avoir des effets
externes ou non transactionnels. Les arguments nommés propres au pilote, REF CURSOR, paramètres structurés/LOB,
résultats multiples et arguments par défaut ne font pas partie de ce contrat portable. N'accorder EXECUTE que
sur une liste approuvée de procédures et traiter chaque appel comme une écriture réelle.

| Type | Libellé | Opérations |
|---|---|---|
| `activemq` | Apache ActiveMQ | publish (W), receive (S), peek (R) |
| `azure-blob` | Azure Blob Storage (files) | read (S), write (W), list (S) |
| `ban` | Base Adresse Nationale (France) | geocode (R), reverse (R) |
| `bigquery` | Google BigQuery | query (S), get (R), insert (W), upsert (W), catalog (S), profile (S), sample (S) |
| `azure-service-bus` | Microsoft Azure Service Bus | publish (W), receive (S), peek (R), complete (W), abandon (W) |
| `azure-sql` | Azure SQL Database | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `companies-house` | Companies House (UK) | get (R), search (S), officers (S), pscs (S), filings (S) |
| `creditsafe` | Creditsafe Connect | search (S), report (R), find (R) |
| `databricks` | Databricks (SQL Statement Execution API) | query (S), lookup (R), merge (W), insert (W), execute (W), describe (R) |
| `dnb` | Dun & Bradstreet Direct+ | match (S), bestMatch (R), dataBlocks (R), enrich (R), familyTree (R), monitoringUpdates (S), registrationAdd (W), registrationRemove (W) |
| `dynamics365` | Microsoft Dynamics 365 (Dataverse) | get (R), query (S), create (W), update (W), upsert (W) |
| `dynamics-business-central` | Microsoft Dynamics 365 Business Central | get (R), query (S), create (W), update (W), upsert (W) |
| `gcs` | Google Cloud Storage (files) | read (S), write (W), list (S) |
| `gleif` | GLEIF (LEI) | lei (R), searchByRegistration (S), searchByName (S), directParent (R), ultimateParent (R) |
| `google-geocoding` | Google Geocoding | geocode (R), reverse (R) |
| `handelsregister` | Handelsregister (OffeneRegister mirror) | search (S), get (R) |
| `h2` | Base de données H2 | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `hubspot` | HubSpot CRM | get (R), query (S), create (W), update (W), upsert (W) |
| `jdbc` | JDBC database (generic) | query (S), get (R), insert (W), update (W), upsert (W), procedure (W), execute (W) |
| `jde-ais` | JD Edwards EnterpriseOne (AIS) | query (S), get (R), orchestration (W), orchestrationQuery (S), form (W) |
| `jms` | Messagerie JMS | publish (W), receive (S), peek (R) |
| `kafka` | Apache Kafka | publish (W), consume (S), peek (R), cdc (S, Debezium, docs/CDC.md), catalog (S), profile (S), sample (S) |
| `loqate` | Loqate address verification | verify (R), find (S), retrieve (R) |
| `melissa` | Melissa Data | address (R), email (R), phone (R) |
| `mysql` | Base de données MySQL | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `netsuite` | Oracle NetSuite (SuiteTalk REST/SuiteQL) | get (R), query (S), create (W), update (W), upsert (W) |
| `oracle-database` | Base de données Oracle | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `opencorporates` | OpenCorporates | get (R), search (S) |
| `opensanctions` | OpenSanctions (OFAC, UN, EU sanctions and PEPs) | screen (R), search (S), entity (R) |
| `oracle-fusion` | Oracle Fusion Cloud (REST) | get (R), query (S), create (W), update (W), upsert (W) |
| `orbis` | Bureau van Dijk Orbis | get (R), search (S), match (R) |
| `postgresql` | PostgreSQL | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `rabbitmq` | RabbitMQ | publish (W), receive (S), peek (R) |
| `rest` | Generic JSON REST |  |
| `s3` | Amazon S3 (files) | read (S), write (W), list (S) |
| `salesforce` | Salesforce (REST, OAuth 2.0) | get (R), getByExternalId (R), query (S), create (W), update (W), upsert (W), describe (R) |
| `sap-odata` | SAP (OData v2 / v4) | get (R), query (S), create (W), update (W), upsert (W), describe (R) |
| `servicenow` | ServiceNow (Table API) | get (R), query (S), create (W), update (W), upsert (W) |
| `sharepoint` | Microsoft SharePoint Online (Microsoft Graph) | site (R), listItems (S), getItem (R), createItem (W), updateItem (W), listFiles (S), downloadTextFile (R), uploadFile (W) |
| `sql-server` | Microsoft SQL Server | query (S), get (R), insert (W), update (W), upsert (W), execute (W) |
| `sftp` | SFTP (files) | read (S), write (W), list (S) |
| `sap-ariba` | SAP Ariba | getSupplier (R), searchSuppliers (S), upsertSupplier (W), getPurchaseOrder (R), searchPurchaseOrders (S), acknowledgePurchaseOrder (W) |
| `powerbi` | Microsoft Power BI (REST + Scanner API) | catalog (S), lineage (S), usage (S), refreshes (S), workspaces (S), query (S, DAX), scan (R) |
| `collibra` | Collibra Data Intelligence Platform (REST API v2) | search (S), get (R), terms (S), types (R), upsert (W), relate (W), import (W), delete (W) |
| `sirene` | INSEE Sirene | siren (R), siret (R), headOffice (R) |
| `snowflake` | Snowflake (SQL REST API) | query (S), lookup (R), merge (W), insert (W), execute (W), describe (R) |
| `sap-successfactors` | SAP SuccessFactors | getPerson (R), queryPeople (S), queryEmployment (S), queryUsers (S), createPerson (W), updatePerson (W), upsertPerson (W), describePeople (R) |
| `vies` | VIES (EU VAT validation) | check (R) |
| `webhook` | Inbound webhook (HTTP POST) | receive (S) |
| `workday` | Workday (REST and RaaS) | workers (S), worker (R), resource (S), raas (S) |
| `redshift` | Amazon Redshift (JDBC profile, endpoint URL `jdbc:redshift://…`) | the `jdbc` operations |
| `llm` | LLM : Claude, OpenAI, Google Gemini, Azure OpenAI / Foundry, Mistral AI, Ollama (enrichissement par IA, confiance 30) | complete, extract, classify, match (R) |
| `documents` | Documents (PDF, DOCX, texte ; éléments DAMA ; images avec `vision`) lus par un connecteur `llm`, valeurs avec confiance et source | list (S), text (R), extract (R), extractAll (S), annotate (W) |
| `soap` | Service web SOAP générique (propriétés : op.x.body, action, result) | une par op.x.body |
| `graphql` | API GraphQL générique (propriétés : op.x.query, result) | une par op.x.query |
| `ebx` | Une autre instance EBX (Data Services) | read (R), search (S), write (W) |
| `chat-webhook` | Webhook entrant Slack / Microsoft Teams | notify (W) |
| `slack` | API Web de Slack avec un jeton de bot | post (W), notify (W), update (W), dm (W), channels (S) |
| `inpi` | INPI Registre National des Entreprises | company (R), search (S) |
| `openfda` | openFDA : dispositifs GUDID, médicaments NDC | udi (R), deviceSearch (S), drug (R) |
| `openlineage` | OpenLineage (catalogue de données, Marquez, DataHub...) | emit (W) |

Le détail de chaque type (authentification, propriétés, format des réponses) est dans la description de
son connecteur amorcé, et dans le README du module.

## 18. Dépendances : quel jar pour quel connecteur

Le module lui-même n'a besoin que de l'API d'ON EBX (`ebx.jar`, fourni par ON EBX) et de Jackson 2.18.12 avec l'API SLF4J,
que le WAR embarque dans son `WEB-INF/lib` (un `lib/` ON EBX standard n'a ni l'un ni l'autre). Toute autre bibliothèque est
**optionnelle** : un type de connecteur dont la bibliothèque manque n'est simplement pas enregistré (le
registre charge ces types sans bruit), les autres fonctionnent. Déposez les jars dans le `lib` partagé de
Tomcat (le dossier du `common.loader`, `ebx-ps-fasttrack-server/lib` dans les workspaces FastTrack) ou
dans `ebx-connectors/WEB-INF/lib`, et ajoutez-les au `.classpath` Eclipse du projet serveur pour compiler.

| Types de connecteurs | Bibliothèque | Coordonnées Maven (version testée) | Notes |
|---|---|---|---|
| Import OpenAPI en **YAML** (`rest` créé depuis une spécification YAML) | `jackson-dataformat-yaml`, `snakeyaml` | `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.12`, `org.yaml:snakeyaml:2.3` | embarquées par le WAR depuis la 1.4.1 (chargées par réflexion ; sans elles les spécifications JSON fonctionnent) |
| `kafka` | `kafka-clients` (+ `slf4j-api`, embarqué par le WAR) | `org.apache.kafka:kafka-clients:3.6.1` | codecs de compression (`lz4-java`, `snappy-java`, `zstd-jni`) seulement si les topics les utilisent ; SASL/SSL par les propriétés du connecteur |
| `sftp` | `jsch` | `com.github.mwiede:jsch:0.2.16` (ou `com.jcraft:jsch:0.1.55`) | mot de passe ou clé privée |
| `redshift` | le pilote JDBC Amazon Redshift | `com.amazon.redshift:redshift-jdbc42:2.1.x` | copié à la main dans `WEB-INF/lib` ; la classe du pilote est chargée de là, jamais depuis une propriété du connecteur |
| `jdbc`, `snowflake`, `databricks`, `bigquery` (mode JDBC) | le pilote JDBC de la base | PostgreSQL `org.postgresql:postgresql:42.7.x`, H2 (tous deux livrés avec FastTrack) ; Snowflake `net.snowflake:snowflake-jdbc:3.14.x` ; Databricks `com.databricks:databricks-jdbc:2.6.x` ; BigQuery : le zip du pilote JDBC Simba/Google | classe du pilote par la propriété `driver`, sinon trouvée par `DriverManager` depuis l'URL |
| `s3`, `azure-blob`, `gcs`, `sharepoint`… | aucune | | appels REST HTTPS avec les identifiants du connecteur (pas de SDK éditeur) |
| tous les types REST (`gleif`, `sirene`, `vies`, `melissa`, `salesforce`, `sap-odata`, `llm`, `graphql`, `openfda`, `inpi`, `ebx`, `chat-webhook`, `rest` générique…) | aucune | | `java.net.http.HttpClient` si la JVM est en Java 11+, `HttpURLConnection` sinon |
| `soap` | aucune | | le parseur XML du JDK (`javax.xml`) |
| `nominatim` (exemple d'extension) | aucune | | son jar `ebx-connectors-extension-sample.jar` dans `WEB-INF/lib` |

Pour déplacer le module vers un autre workspace EBX : copier `ebx-connectors/` (projet Eclipse, avec
`target/classes` si Eclipse ne le reconstruit pas) et `ebx-connectors-extension-sample/`, le contexte
Tomcat `conf/Catalina/localhost/ebx-connectors.xml` (docBase = `src/main/webapp`, `PostResources` qui
monte `target/classes` sur `/WEB-INF/classes`), les jars optionnels ci-dessus dans le `lib` du serveur, et
les lignes `<classpathentry kind="lib" .../>` correspondantes dans le `.classpath` du projet serveur. Le
premier démarrage crée et amorce le dataset Connectors.
