# ebx-connectors — module EBX de connecteurs (version française)

*La version de référence de ce document est [README.md](README.md), en anglais.*

Module EBX autonome et partageable : connecteurs vers des applications et services externes,
mappings bidirectionnels avec transformations, concepteur graphique, et points d'entrée
partout dans EBX (service utilisateur, workflow, tâche planifiée, déclencheur, API REST).

**Guide d'utilisation complet, mappings en tête, avec exemples et fiche pour un agent d'IA : [GUIDE.md](GUIDE.md) (English), [GUIDE.fr.md](GUIDE.fr.md) (français).**

**Compatibilité de déploiement et retour arrière : [../docs/UPGRADE-ROLLBACK.md](../docs/UPGRADE-ROLLBACK.md) (en anglais).**

## Contenu

| Emplacement | Rôle |
|---|---|
| `src/main/java/com/on/ebx/connect/spi` | contrat d'un connecteur (`Connector`, `Operation`, `ConnectorConfig`, `FieldInfo`, `TablePublisher` pour publier un produit entier) |
| `src/main/java/com/on/ebx/connect/core` | moteur : JSON, HTTP, transformations, expressions, mappings, registre, service, publication de produit (`ProductPublisher`) |
| `src/main/java/com/on/ebx/connect/connectors` | 96 types de connecteurs (GLEIF, Sirene, VIES, BAN, Salesforce, SAP, Kafka, JDBC, S3, Snowflake, Databricks, Power BI, Collibra, OpenLineage, Slack…) |
| `src/main/java/com/on/ebx/connect/ebx` | intégration EBX : installateur, concepteur, service, workflow, planificateur, déclencheur, REST |
| `src/main/webapp/WEB-INF/ebx/module.xml` | déclaration du module `ebx-connectors` et du bean de workflow |
| `src/main/webapp/WEB-INF/ebx/schemas/Connectors.xsd` | modèle de la configuration (dataspace / dataset `Connectors`) |
| `src/main/webapp/WEB-INF/web.xml` | enregistrement de l'API REST (REST Toolkit) |
| `src/main/webapp/www/common/{jscripts,stylesheets}/connect` | front du concepteur de mapping |

Le nom du module, `ebx-connectors`, n'a ni espace ni caractère spécial : les clés de service
(`ebx-connectors@ConnectorSync`) respectent le motif exigé par EBX.

## Dépendances

Le WAR construit par `build-test.sh` embarque dans `WEB-INF/lib` les bibliothèques de `runtime-libs/` (Jackson 2.14.2, API SLF4J 1.7.32, Kafka, JSch, pilotes...) : un `lib/` ON EBX standard n'a ni Jackson ni SLF4J (détail : `docs/OPERATIONS.md` sections 1.1 et 2.6). Pour compiler depuis les sources, dans le classpath commun de Tomcat (`lib/`) ou dans `WEB-INF/lib` du module :

- `ebx.jar` (installation ON EBX standard ; `ebx-lib.jar` sous FastTrack, même jar) et `servlet-api.jar` (fournis par ON EBX et Tomcat),
- `jackson-core`, `jackson-databind`, `jackson-annotations` 2.14.2 (obligatoires ; embarqués par le WAR, la copie du WAR l'emporte sur celle d'un `lib/` FastTrack),
- `jackson-dataformat-yaml`, `snakeyaml` (import de spécifications OpenAPI en YAML, optionnel),
- `kafka-clients` (connecteur Kafka, optionnel), `jsch` (connecteur SFTP, optionnel), pilotes JDBC (optionnel).

Aucune dépendance au module FastTrack ni au toolbox presales.

## Installation

1. Déployer le module comme webapp Tomcat sous le contexte `/ebx-connectors` (WAR, ou contexte
   pointant sur `src/main/webapp` avec `target/classes` monté sur `/WEB-INF/classes`, cf.
   `ebx-ps-fasttrack-server/conf/Catalina/localhost/ebx-connectors.xml`).
2. Redémarrer : au démarrage, le module crée le dataspace et le dataset `Connectors` et les amorce
   (une soixantaine de connecteurs d'exemple avec secrets `TO_BE_SET`, mappings de démonstration sur `/root/Companies`).
3. Renseigner les clés dans la table Connector (Data > Connectors).

Si un dataset `Connectors` existe déjà avec le modèle d'un autre module (ancienne installation dans
FastTrack), le module le signale dans le `kernel.log` et interrompt l'installation sans modifier ni
supprimer les données existantes. Exporter la configuration, migrer explicitement le modèle, puis la
ré-importer avec `GET/POST /rest/v1/config` ; le démarrage du module ne réalise jamais cette migration
destructive automatiquement.

Au redémarrage depuis Eclipse, attendre la fin de la reconstruction des projets : Tomcat lancé pendant
qu'Eclipse réécrit `target/classes` peut ne pas trouver l'écouteur d'un module, qui démarre alors sans
s'enregistrer (EBX attend « not yet registered »).

## API REST (REST Toolkit)

Base : `/ebx-connectors/rest/v1`, authentification EBX standard (Basic, jeton, session).

| Méthode et chemin | Rôle |
|---|---|
| `GET /connectors` | types disponibles, connecteurs configurés (et s'ils ont leurs secrets) |
| `POST /connectors/openapi?id=&url=` ou corps JSON/YAML | crée un connecteur `rest` depuis une spécification OpenAPI 3 (`&replace=true`, `&dryRun=true`) |
| `GET /mappings[?table=/root/Companies]` | mappings |
| `POST /sync/{mapping}?key=<pk>` | un mapping sur un enregistrement (`mapping` = id ou libellé ; la forme `connecteur/opération` passe par `enrich?mappings=`, Tomcat refusant le `/` encodé dans un chemin) |
| `POST /enrich?dataset=&table=&key=&mappings=2,1,30` | enchaînement ordonné de mappings entrants (`&connectors=GLEIF/lei,VIES` accepté) |
| `POST /publish` | publie un produit de données entier vers un connecteur `TablePublisher` (Snowflake) : un commit atomique pour chaque table d'un ou plusieurs mappings sortants racines, puis (au mieux) le sens métier : commentaires de tables et de colonnes, tags (`EBX_PRODUCT`, `EBX_GLOSSARY_TERM`…) et une semantic view `EBX_<PRODUIT>_SEMANTIC` portant les synonymes du glossaire — voir GUIDE § 10.2 ; 200 pour OK/DRY_RUN, 422 pour ERROR |
| `POST /webhook/{flux}` | import d'un document (JSON, ndjson, csv) par un mapping de flux ; `GET` = contrôle de santé |

Tous acceptent `?dryRun=true` (simulation) et `?predicate=` à la place de `key`. Le webhook n'a qu'un
mode : une session EBX (Basic, jeton ou SSO) d'un utilisateur qui a le droit RUN, l'import tournant sous
cet utilisateur. Les modes « secret partagé » (`X-Webhook-Secret`) et « signature » ont été retirés (audit C2-10) ;
envoyer le document depuis un compte EBX technique.

```bash
curl -u admin:admin -X POST "http://localhost:8080/ebx-connectors/rest/v1/enrich?dataset=Companies&table=/root/Companies&key=511&mappings=2,1,30,32"
```

## Listes de valeurs calculées

Deux champs de la configuration sont des énumérations programmées (`osd:constraintEnumeration`),
alimentées à l'exécution et non par une table de codes :

- `Connector/type` (`ConnectorTypeEnumeration`) : les types enregistrés dans le module, avec leur
  libellé. Un nouveau type apparaît dès que sa classe est enregistrée (classe intégrée, extension
  `META-INF/services`, ou `rest` pour une API déclarée par propriétés) : rien à saisir à la main, et une
  valeur inconnue est une erreur de validation.
- `Mapping/operation` (`MappingOperationEnumeration`) : les opérations du connecteur choisi sur la
  même ligne, avec leur genre et leur description ; la liste se recalcule quand `connector` change.
  Connecteur inconnu ou inactif : simple avertissement, pour pouvoir préparer un mapping à l'avance.

## Formulaire de la table Mapping

`ebx/ui/MappingForm` (déclaré par `<recordForm>` dans Connectors.xsd) remplace le formulaire par
défaut : onglet d'accueil avec un bouton **Concevoir le mapping graphiquement** (ouvre le service
`MappingDesigner` sur l'enregistrement, en prévisualisation), puis deux sections « Ce que fait le
mapping » (libellé, connecteur, opération, sens, actif, description) et « Où il s'applique »
(espace, jeu de données, table, racine de la réponse) ; onglets Entrées, Règles, Sous-objets et Flux
d'import, chacun avec son explication et son association affichée en table. En création, le bouton
et les associations sont remplacés par « enregistrez d'abord ». Les champs sont lus depuis le modèle :
un champ ajouté au XSD apparaît sans recompilation. Toute la documentation des champs (opération,
sens, connecteur, jeu de données, actif…) est portée par le modèle, donc visible aussi dans le
formulaire par défaut et dans l'aide contextuelle.

## Formulaire de la table Connector

L'onglet *Connexion et configuration* indique quels identifiants saisir pour l'authentification choisie : les
modes proposés par le type de connecteur en cartes, un état obligatoire / optionnel / déduit / non utilisé et un
libellé contextuel sur chaque champ (« Clé API / jeton » devient « Client secret » en OAuth 2.0, « Chaîne de
connexion » pour Azure Service Bus), quoi saisir, où l'obtenir chez le fournisseur et ce que le module envoie, et
un avertissement quand un secret collé dépasse les 255 caractères du champ. Règles dans `core/CredentialGuide`,
textes dans les clés `credentials.*` des bundles (GUIDE § 3.1).

## Tâche script de workflow

Le bean `com.on.ebx.connect.ebx.workflow.ConnectorScriptTaskBean` (déclaré dans module.xml, module
`ebx-connectors` à indiquer dans le modèle de workflow) exécute un ou plusieurs mappings sur un
enregistrement ou une table.

| Entrée | Rôle |
|---|---|
| `dataspace`, `dataset` | cible (ex. `Companies` / `Companies`) |
| `xpath` | un enregistrement : `/root/Companies[./Identification/Id=512]` |
| `tablePath`, `predicate`, `maxRecords` | ou toute une table, filtrée, plafonnée (200) |
| `mappings` | liste ordonnée d'ids, libellés ou `connecteur/opération` : `37,38,1` — la forme sans ambiguïté |
| `connectors` | ou liste ordonnée de connecteurs (tous leurs mappings entrants de la table) |
| `connector`, `operation`, `direction` | ou un seul mapping (forme historique) |
| `dryRun`, `failOnError` | simulation ; mettre l'étape en erreur si un appel échoue (défaut : non) |

Sorties : `report` (une ligne par appel), `changedCount`, `okCount`, `errorCount`, `status`
(OK, PARTIAL, ERROR, NO_DATA). Sur un enregistrement, les mappings s'enchaînent dans l'ordre et
chacun voit ce que le précédent a écrit (Wikidata trouve le LEI, GLEIF l'exploite).

Modèle de workflow d'exemple `ConnectorEnrichment` (publication du même nom, créé par MCP le
2026-09-05) : une seule tâche script, variables d'entrée `dataspace`, `dataset`, `xpath`, `tablePath`,
`predicate`, `mappings`, `connectors`, `dryRun`, `maxRecords`, sorties `report`, `changedCount`,
`okCount`, `errorCount`, `status`. Lancement : Workflow > ConnectorEnrichment, ou par MCP
`workflow_execution_launch`. EBX charge la classe du bean et lit sa déclaration au démarrage : toute
modification de ses propriétés demande un redémarrage.

## OpenLineage et le Data catalog

- Un connecteur de type **`openlineage`** (URL de base = le point d'entrée du puits : le
  `/ebx-data-catalog/rest/v1/openlineage` du Data catalog ON EBX, Marquez, DataHub) suffit pour que
  **chaque exécution sortante** s'y décrive en `RunEvent` OpenLineage : table EBX en entrée, objet du
  connecteur en sortie, une entrée de lineage colonne par règle, les compteurs (`core/OpenLineageEmitter`,
  GUIDE § 3.7). Entrant sur demande ; simulations et exécutions vides n'envoient rien.
- Chaque mapping est aussi décrit **dès qu'il est déclaré**, avant toute exécution : les déclencheurs
  de `Mapping` / `MappingInput` / `MappingRule` le confient à `core/DeclaredLineage`, qui envoie un
  `JobEvent` OpenLineage une fois les modifications terminées (un retrait quand le mapping est
  désactivé, supprimé ou renommé), balaie tous les mappings au démarrage et répond à
  `POST /v1/lineage/declare` et à l'outil MCP `connectors_lineage_declare`. Le catalogue montre le
  flux en `DECLARED`, puis en `OPENLINEAGE` après sa première exécution (GUIDE § 3.7, propriété
  `emit.declared` du puits).
- Le connecteur **`ebx`** porte les opérations `catalog`, `profile`, `sample` et `lineage`
  (`connectors/EbxCatalog`, GUIDE § 3.8) : une instance EBX — celle-ci par ses propres Data Services —
  est inventoriée, profilée et tracée par le catalogue comme une base de données.

## Développement

Sans Maven : `javac --release 8` avec les jars ci-dessus, sortie dans `target/classes`, servie en
direct par Tomcat. Le front du concepteur (`www/…/connect/mapping-designer.{js,css}`) est servi
depuis les sources : retouchable sans redémarrage. Le modèle `Connectors.xsd` s'édite à la main
(`tools/build_xsd.py` n'en connaît que 9 tables sur 27 : le lancer sur le fichier perdrait les autres ; `tools/schema_diff.py` compare deux versions). Documentation détaillée des
connecteurs, du mapping et du concepteur : le README du module (anglais).

## Modèle du client

`-Debx.connectors.seedMappings=auto|always|never` : en `auto` (défaut), les mappings de démonstration ne sont
amorcés que si `/root/Companies` existe, avec les seules règles dont les champs existent. Trois contraintes de
validation (avertissements) signalent ensuite tout mapping, règle ou entrée qui ne résout pas dans le modèle.

## Étendre par un jar (sans les sources)

`./build-spi.sh` produit `dist/ebx-connectors-spi.jar` (+ sources) : le contrat à donner à un développeur tiers.
Son connecteur (classe implémentant `com.on.ebx.connect.spi.Connector` + fichier
`META-INF/services/com.on.ebx.connect.spi.Connector`) se dépose en jar dans `WEB-INF/lib/`. Exemple complet :
projet `../ebx-connectors-extension-sample` (Nominatim), déjà déployé ici.

## Documentation

- `GUIDE.md` (anglais) / `GUIDE.fr.md` (français) : référence complète (expressions, transformations, API REST, SPI Java).
- `docs/USER-GUIDE.md` (et `docs/USER-GUIDE.html`, autonome) : guide utilisateur en anglais avec captures d'écran de chaque écran (configuration, concepteur, enrichissement, confiance et conflits).

## Deltas sortants et rejeu

Portées `unsent`, `changed`, `failed`, `dataspaceDelta` pour les mappings sortants (table `OutboundState` :
empreinte du dernier document envoyé, échecs, suppressions via `deleteOperation`, politique de nouvel essai) ;
chaque exécution a un `runId`, `POST /replay/{runId}` rejoue ses échecs. Voir GUIDE.fr.md section 10.1.

## Publier un produit de données

Là où les deltas ci-dessus gardent la cible d'un seul mapping continuellement synchronisée,
`connectors_publish` / `POST /publish` / le bean de workflow `ProductPublishTaskBean` envoient **un ou
plusieurs mappings sortants racines, tous sur le même connecteur**, comme un produit entier selon un
contrat : un commit atomique pour chaque table cible (périmètre v1 : Snowflake uniquement,
rechargement complet uniquement — pas de publication incrémentale, pas d'isolation de snapshot de la
lecture source). L'entrée `table` de chaque mapping nomme sa table cible ; ses règles et ses enfants
actifs deviennent les colonnes de la table (une règle imbriquée ou un mapping enfant devient une
colonne `VARIANT`/JSON). Chaque appel, simulations comprises, est enregistré dans la table
`Publication` par `runId`, avec le rapport complet en JSON. Voir GUIDE.fr.md section 10.2.

## Conflits (politique TRUSTED)

Les propositions refusées parce que la source est moins fiable que celle qui a écrit la valeur vont dans la
table `Conflict`. Un steward les tranche avec le User Service « Gérer les conflits des connecteurs »
(espace de données, table, enregistrement, ou tâche utilisateur de workflow `ebx-connectors@ConflictManager`),
ou en REST (`GET /conflicts`, `POST /conflicts/{id}/accept|reject`). Les réponses REST et le bean de workflow
signalent les conflits (`hasConflicts`, `conflictCount`). Voir GUIDE.fr.md section 4.5.
