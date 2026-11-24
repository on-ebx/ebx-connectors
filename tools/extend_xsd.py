# -*- coding: utf-8 -*-
"""Companies rev 2 (2026-09-04): patch the deployed Companies.xsd in place.

The file is DMA-generated and hand-maintained (triggers, widgets, replication, custom form):
rather than regenerating it, this script inserts new XML at four anchors and leaves every
existing byte alone. Idempotent: refuses to run twice on the same file (marker comment).

    python3 extend_xsd.py            # patches the deployed XSD (backup: Companies.xsd.bak-20260904)
    python3 extend_xsd.py --check    # only prints what would be inserted, touches nothing
"""
import re, sys, os

XSD = os.environ.get("COMPANIES_XSD", os.path.join(os.environ.get("EBX_WORKSPACE", "."),
       "ebx-ps-fasttrack-web/src/main/webapp/WEB-INF/ebx/schemas/Companies.xsd"))
MARKER = "<!--Companies rev 2 (2026-09-04): legal information, contact, lifecycle, governance, identifiers, addresses, bank accounts, contacts, relationships, NACE, ELF and RA code lists.-->"

XS = {'string': 'xs:string', 'decimal': 'xs:decimal', 'int': 'xs:int', 'date': 'xs:date',
      'dateTime': 'xs:dateTime', 'boolean': 'xs:boolean', 'text': 'osd:text', 'id': 'id', 'resource': 'osd:resource'}
COUNTRY = ("Geographies", "Geographies", "/root/Country")
CURRENCY = ("Currencies", "Currencies", "/root/Currency")
EMAIL = r"[^@\s]+@[^@\s]+\.[^@\s]+"
URL = r"https?://.+"


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')


class F(object):
    def __init__(self, name, type='string', en=None, fr=None, desc_en=None, desc_fr=None, fk=None,
                 pattern=None, enum=None, min=0, max=1, maxLen=None, children=None, assoc=None,
                 default=None, constraint=None, columns=None, hidden_ds=False, enumClass=None, uiBean=None, function=None):
        self.name, self.type, self.en = name, type, en or name
        self.fr = fr or self.en
        self.desc_en, self.desc_fr = desc_en, desc_fr or desc_en
        self.fk = fk            # "/root/Table" or (container, branch, tablePath)
        self.pattern, self.enum, self.min, self.max, self.maxLen = pattern, enum, min, max, maxLen
        self.children, self.assoc, self.default, self.constraint = children, assoc, default, constraint
        # EBX mapped tables refuse an unbounded string: every enumerated string gets a length facet.
        if self.enum is not None and self.maxLen is None and self.type == 'string':
            self.maxLen = 40
        self.columns = columns  # association tabular view columns
        self.enumClass = enumClass  # programmatic enumeration (osd:constraintEnumeration)
        self.uiBean = uiBean  # custom widget factory (osd:defaultView/widget osd:class)
        self.function = function  # computed value (osd:function class)
        self.hidden_ds = hidden_ds


class T(object):
    def __init__(self, name, en, fr, pk, fields, label=None, label_fr=None, desc_en=None, desc_fr=None, recordForm=None, trigger=None):
        self.name, self.en, self.fr, self.pk, self.fields = name, en, fr, pk, fields
        self.trigger = trigger  # osd:trigger class on the table
        self.label, self.label_fr, self.desc_en, self.desc_fr = label, label_fr, desc_en, desc_fr
        self.recordForm = recordForm  # osd:class of a UIForm rendering the record


def doc(ind, en, fr, desc_en=None, desc_fr=None):
    o = ['%s<xs:documentation xml:lang="en-US">' % ind, '%s    <osd:label>%s</osd:label>' % (ind, esc(en))]
    if desc_en:
        o.append('%s    <osd:description>%s</osd:description>' % (ind, esc(desc_en)))
    o += ['%s</xs:documentation>' % ind, '%s<xs:documentation xml:lang="fr-FR">' % ind,
          '%s    <osd:label>%s</osd:label>' % (ind, esc(fr))]
    if desc_fr:
        o.append('%s    <osd:description>%s</osd:description>' % (ind, esc(desc_fr)))
    o.append('%s</xs:documentation>' % ind)
    return o


def field(f, ind):
    o = []
    if f.assoc:
        occ = ' minOccurs="0" maxOccurs="0"'  # EBX: an association node must set both occurrences to 0
    else:
        occ = ' minOccurs="%d" maxOccurs="%s"' % (f.min, 'unbounded' if f.max == 0 else str(f.max))
    if f.default is not None:
        occ += ' default="%s"' % esc(str(f.default))
    inline = f.enum is not None or f.pattern is not None or f.maxLen is not None
    if f.children is not None or inline:
        o.append('%s<xs:element name="%s"%s>' % (ind, f.name, occ))
    else:
        o.append('%s<xs:element name="%s" type="%s"%s>' % (ind, f.name, XS[f.type], occ))
    i1 = ind + '    '
    o.append('%s<xs:annotation>' % i1)
    o += doc(i1 + '    ', f.en, f.fr, f.desc_en, f.desc_fr)
    app = []
    if f.fk or f.constraint or getattr(f, 'enumClass', None):
        app.append('<osd:otherFacets>')
        if f.fk:
            app.append('    <osd:tableRef>')
            if isinstance(f.fk, tuple):
                app.append('        <container>%s</container>' % f.fk[0])
                app.append('        <branch>%s</branch>' % f.fk[1])
                app.append('        <tablePath>%s</tablePath>' % f.fk[2])
            else:
                app.append('        <tablePath>%s</tablePath>' % f.fk)
            app.append('    </osd:tableRef>')
        if f.constraint:
            app.append('    <osd:constraint class="%s"/>' % f.constraint)
        if getattr(f, 'enumClass', None):
            app.append('    <osd:constraintEnumeration class="%s"/>' % f.enumClass)
        app.append('</osd:otherFacets>')
    if getattr(f, 'function', None):
        app.append('<osd:function class="%s"/>' % f.function)
    if getattr(f, 'encryptionClass', None):  # osd:password: reversible encryption instead of the default SHA-512 hash
        app += ['<osd:uiBean class="com.orchestranetworks.ui.UIPassword">', '    <encryptionClass>%s</encryptionClass>' % f.encryptionClass, '</osd:uiBean>']
    if getattr(f, 'uiBean', None):
        app += ['<osd:defaultView>', '    <widget osd:class="%s"/>' % f.uiBean, '</osd:defaultView>']  # UIWidgetFactory, as in the EBX add-on models (attribute osd:class)  # UIWidgetFactory: osd:defaultView/widget (osd:uiBean = legacy UIBeanEditor)
    if f.assoc:
        app += ['<osd:association>', '    <tableRefInverse>',
                '        <fieldToSource>%s</fieldToSource>' % f.assoc,
                '    </tableRefInverse>', '</osd:association>', '<osd:defaultView>']
        if f.hidden_ds:
            app.append('    <hiddenInDataServices>true</hiddenInDataServices>')
        app.append('    <displayMode>tab</displayMode>')
        if f.columns:
            app.append('    <tabularView>')
            for c in f.columns:
                app.append('        <column>%s</column>' % c)
            app.append('        <initialQueryEnabled>true</initialQueryEnabled>')
            app.append('    </tabularView>')
        app.append('</osd:defaultView>')
    if f.children is not None:
        app += ['<osd:defaultView>', '    <displayMode>expanded</displayMode>', '</osd:defaultView>']
    if app:
        o.append('%s    <xs:appinfo>' % i1)
        o += ['%s        %s' % (i1, a) for a in app]
        o.append('%s    </xs:appinfo>' % i1)
    o.append('%s</xs:annotation>' % i1)
    if f.children is not None:
        o += ['%s<xs:complexType>' % i1, '%s    <xs:sequence>' % i1]
        for c in f.children:
            o += field(c, i1 + '        ')
        o += ['%s    </xs:sequence>' % i1, '%s</xs:complexType>' % i1]
    elif inline:
        o += ['%s<xs:simpleType>' % i1, '%s    <xs:restriction base="%s">' % (i1, XS[f.type])]
        for v, le, lf in (f.enum or []):
            o += ['%s        <xs:enumeration value="%s">' % (i1, esc(v)), '%s            <xs:annotation>' % i1,
                  '%s                <xs:documentation xml:lang="en-US">%s</xs:documentation>' % (i1, esc(le)),
                  '%s                <xs:documentation xml:lang="fr-FR">%s</xs:documentation>' % (i1, esc(lf)),
                  '%s            </xs:annotation>' % i1, '%s        </xs:enumeration>' % i1]
        if f.maxLen:
            o.append('%s        <xs:maxLength value="%d"/>' % (i1, f.maxLen))
        if f.pattern:
            o.append('%s        <xs:pattern value="%s"/>' % (i1, esc(f.pattern)))
        o += ['%s    </xs:restriction>' % i1, '%s</xs:simpleType>' % i1]
    o.append('%s</xs:element>' % ind)
    return o


def table(t, ind):
    o = ['%s<xs:element name="%s" minOccurs="0" maxOccurs="unbounded">' % (ind, t.name)]
    i1 = ind + '    '
    o.append('%s<xs:annotation>' % i1)
    o += doc(i1 + '    ', t.en, t.fr, t.desc_en, t.desc_fr)
    o += ['%s    <xs:appinfo>' % i1, '%s        <osd:table>' % i1,
          '%s            <primaryKeys>%s</primaryKeys>' % (i1, ''.join('/%s ' % p for p in t.pk))]
    if getattr(t, 'recordForm', None):
        o.append('%s            <recordForm osd:class="%s"/>' % (i1, t.recordForm))
    if t.label:
        o.append('%s            <defaultLabel>%s</defaultLabel>' % (i1, esc(t.label)))
    if t.label_fr:
        o.append('%s            <defaultLabel xml:lang="fr-FR">%s</defaultLabel>' % (i1, esc(t.label_fr)))
    o += ['%s            <defaultView>' % i1, '%s                <defaultViewForGroups>expanded</defaultViewForGroups>' % i1,
          '%s            </defaultView>' % i1, '%s        </osd:table>' % i1]
    if getattr(t, 'trigger', None):
        # a table trigger sits in xs:appinfo next to osd:table, never inside it (inside, 'trigger' is a text property of the table bean)
        o += ['%s        <osd:trigger class="%s">' % (i1, t.trigger), '%s            <enabled>true</enabled>' % i1, '%s        </osd:trigger>' % i1]
    o += ['%s    </xs:appinfo>' % i1, '%s</xs:annotation>' % i1, '%s<xs:complexType>' % i1, '%s    <xs:sequence>' % i1]
    for f in t.fields:
        o += field(f, i1 + '        ')
    o += ['%s    </xs:sequence>' % i1, '%s</xs:complexType>' % i1, '%s</xs:element>' % ind]
    return o


# ---------------------------------------------------------------------------------------------
# 1. Fields appended to AdditionalInformation
ADDITIONAL = [
    F('naceCode', en='NACE code', fr='Code NACE', fk='/root/ReferenceData/NaceCode',
      desc_en='Statistical classification of economic activities (NACE Rev. 2).',
      desc_fr='Nomenclature statistique des activités économiques (NACE rév. 2).'),
    F('ultimateParent', en='Ultimate parent', fr='Maison mère ultime', fk='/root/Companies',
      desc_en='Top of the consolidation chain, as published by GLEIF or entered manually.',
      desc_fr='Tête du périmètre de consolidation, selon GLEIF ou saisie manuellement.'),
]

# 2. New groups on Companies
GROUPS = [
    F('LegalInformation', en='Legal information', fr='Informations légales', min=1, children=[
        F('legalName', en='Legal name', fr='Dénomination légale', maxLen=255,
          desc_en='Name as registered with the registration authority.', desc_fr="Nom tel qu'immatriculé auprès de l'autorité d'enregistrement."),
        F('legalForm', en='Legal form', fr='Forme juridique', fk='/root/ReferenceData/LegalForm'),
        F('entityCategory', en='Entity category', fr="Catégorie d'entité", enum=[
            ('GENERAL', 'General', 'Générale'), ('BRANCH', 'Branch', 'Succursale'), ('FUND', 'Fund', 'Fonds'),
            ('SOLE_PROPRIETOR', 'Sole proprietor', 'Entrepreneur individuel'),
            ('RESIDENT_GOVERNMENT_ENTITY', 'Government entity', 'Entité gouvernementale'),
            ('INTERNATIONAL_ORGANIZATION', 'International organisation', 'Organisation internationale')]),
        F('jurisdiction', en='Jurisdiction', fr='Juridiction', fk=COUNTRY),
        F('registrationAuthority', en='Registration authority', fr="Autorité d'immatriculation", fk='/root/ReferenceData/RegistrationAuthority'),
        F('registrationNumber', en='Registration number', fr="Numéro d'immatriculation", maxLen=80,
          desc_en='Identifier assigned by the registration authority (SIREN, Companies House number, ...).',
          desc_fr="Identifiant attribué par l'autorité d'immatriculation (SIREN, numéro Companies House, ...)."),
        F('incorporationDate', 'date', en='Incorporation date', fr='Date de création'),
        F('shareCapital', 'decimal', en='Share capital', fr='Capital social'),
        F('capitalCurrency', en='Capital currency', fr='Devise du capital', fk=CURRENCY),
        F('listed', 'boolean', en='Listed?', fr='Cotée ?', default='false'),
        F('stockExchange', en='Stock exchange', fr='Place de cotation', maxLen=120),
        F('ticker', en='Ticker', fr='Mnémonique boursier', maxLen=20),
        F('isin', en='ISIN', fr='ISIN', maxLen=12, pattern=r'[A-Z]{2}[A-Z0-9]{9}[0-9]'),
        F('employeesCount', 'int', en='Employees', fr='Effectif'),
        F('sizeCategory', en='Size category', fr="Catégorie d'entreprise", enum=[
            ('MICRO', 'Micro (< 10)', 'Micro (< 10)'), ('SMALL', 'Small (10-49)', 'Petite (10-49)'),
            ('MEDIUM', 'Medium (50-249)', 'Moyenne (50-249)'), ('LARGE', 'Large (250-4999)', 'Grande (250-4999)'),
            ('VERY_LARGE', 'Very large (5000+)', 'Très grande (5000+)')]),
        F('annualRevenue', 'decimal', en='Annual revenue', fr="Chiffre d'affaires annuel"),
        F('revenueCurrency', en='Revenue currency', fr='Devise du CA', fk=CURRENCY),
        F('revenueYear', 'int', en='Revenue year', fr='Exercice du CA'),
    ]),
    F('Contact', en='Contact channels', fr='Canaux de contact', min=1, children=[
        F('email', en='Email', fr='Courriel', maxLen=255, pattern=EMAIL),
        F('phone', en='Phone', fr='Téléphone', maxLen=40),
        F('website', en='Website', fr='Site web', maxLen=255, pattern=URL),
    ]),
    F('Lifecycle', en='Lifecycle', fr='Cycle de vie', min=1, children=[
        F('status', en='Status', fr='Statut', default='ACTIVE', enum=[
            ('PROSPECT', 'Prospect', 'Prospect'), ('ACTIVE', 'Active', 'Active'),
            ('SUSPENDED', 'Suspended', 'Suspendue'), ('CLOSED', 'Closed', 'Fermée'), ('MERGED', 'Merged', 'Fusionnée')]),
        F('statusDate', 'date', en='Status date', fr='Date du statut'),
        F('startOfValidity', 'date', en='Start of validity', fr='Début de validité'),
        F('endOfValidity', 'date', en='End of validity', fr='Fin de validité'),
        F('blacklistReason', en='Blacklist reason', fr='Motif de blacklist', maxLen=255),
        F('blacklistDate', 'date', en='Blacklist date', fr='Date de blacklist'),
        F('leiStatus', en='LEI registration status', fr="Statut d'enregistrement du LEI", enum=[
            ('ISSUED', 'Issued', 'Émis'), ('LAPSED', 'Lapsed', 'Expiré'), ('RETIRED', 'Retired', 'Retiré'),
            ('MERGED', 'Merged', 'Fusionné'), ('ANNULLED', 'Annulled', 'Annulé'), ('DUPLICATE', 'Duplicate', 'Doublon'),
            ('PENDING_TRANSFER', 'Pending transfer', 'Transfert en cours'), ('PENDING_ARCHIVAL', 'Pending archival', 'Archivage en cours'),
            ('PENDING_VALIDATION', 'Pending validation', 'Validation en cours'), ('CANCELLED', 'Cancelled', 'Annulé (cancelled)')]),
        F('leiNextRenewalDate', 'date', en='LEI next renewal', fr='Prochain renouvellement du LEI'),
        F('leiEntityStatus', en='LEI entity status', fr="Statut de l'entité (GLEIF)", enum=[
            ('ACTIVE', 'Active', 'Active'), ('INACTIVE', 'Inactive', 'Inactive')]),
    ]),
    F('Governance', en='Governance', fr='Gouvernance', min=1, children=[
        F('completenessScore', 'int', en='Completeness score (%)', fr='Score de complétude (%)',
          desc_en='Share of the expected attributes that are filled in. Computed by the enrichment job.',
          desc_fr="Part des attributs attendus qui sont renseignés. Calculé par le traitement d'enrichissement."),
        F('missingAttributes', 'text', en='Missing attributes', fr='Attributs manquants', maxLen=1000),
        F('enrichmentSource', en='Enrichment sources', fr="Sources d'enrichissement", maxLen=120),
        F('lastEnrichmentDate', 'dateTime', en='Last enrichment', fr='Dernier enrichissement'),
        F('gleifLastUpdate', 'dateTime', en='GLEIF last update', fr='Dernière mise à jour GLEIF'),
        F('wikidataId', en='Wikidata id', fr='Identifiant Wikidata', maxLen=20),
    ]),
]

# 3. New associations on Companies
ASSOCS = [
    F('identifiers', en='Identifiers', fr='Identifiants', max=0, assoc='/root/CompanyIdentifier/company', columns=['/type', '/value', '/issuingAuthority', '/isPrimary']),
    F('addresses', en='Addresses', fr='Adresses', max=0, assoc='/root/CompanyAddress/company', columns=['/type', '/Street', '/Zipcode', '/City', '/Country']),
    F('bankAccounts', en='Bank accounts', fr='Comptes bancaires', max=0, assoc='/root/BankAccount/company', columns=['/label', '/IBAN', '/BIC', '/currency', '/isDefault']),
    F('contacts', en='Contacts', fr='Contacts', max=0, assoc='/root/Contact/company', columns=['/lastName', '/firstName', '/role', '/email', '/phone']),
    F('parentRelationships', en='Parents', fr='Maisons mères', max=0, assoc='/root/CompanyRelationship/fromCompany', columns=['/type', '/toCompany', '/relatedName', '/ownershipPercent', '/status']),
    F('childRelationships', en='Subsidiaries and branches', fr='Filiales et succursales', max=0, assoc='/root/CompanyRelationship/toCompany', columns=['/type', '/fromCompany', '/ownershipPercent', '/status']),
    F('directSubsidiaries', en='Companies reporting to this parent', fr='Sociétés rattachées (champ Parent)', max=0, assoc='/root/Companies/AdditionalInformation/Parent', columns=['/Identification/Name', '/Address/Country']),
]

# 4. New tables at /root
def company_fk(name='company', en='Company', fr='Société'):
    return F(name, en=en, fr=fr, fk='/root/Companies', min=1)

TABLES = [
    T('CompanyIdentifier', 'Company identifiers', 'Identifiants des sociétés', ['id'], [
        F('id', 'id', en='Id', fr='Id', min=1), company_fk(),
        F('type', en='Type', fr='Type', fk='/root/ReferenceData/IdentifierType', min=1),
        F('value', en='Value', fr='Valeur', maxLen=80, min=1),
        F('issuingAuthority', en='Issuing authority', fr='Autorité émettrice', maxLen=255),
        F('validFrom', 'date', en='Valid from', fr='Valide du'), F('validTo', 'date', en='Valid to', fr='Valide au'),
        F('isPrimary', 'boolean', en='Primary?', fr='Principal ?', default='false'),
        F('source', en='Source', fr='Source', maxLen=40),
    ], label='${./type}: ${./value}', desc_en='Every identifier a company carries: LEI, national registration number, VAT, DUNS, ISIN, BIC, ...'),
    T('CompanyAddress', 'Company addresses', 'Adresses des sociétés', ['id'], [
        F('id', 'id', en='Id', fr='Id', min=1), company_fk(),
        F('type', en='Type', fr='Type', fk='/root/ReferenceData/AddressType', min=1),
        F('Number', en='Number', fr='Numéro', maxLen=20), F('Street', en='Street', fr='Rue', maxLen=255),
        F('Street2', en='Address line 2', fr="Complément d'adresse", maxLen=255),
        F('Zipcode', en='ZIP code', fr='Code postal', maxLen=20), F('City', en='City', fr='Ville', maxLen=120),
        F('Region', en='Region', fr='Région', maxLen=20, desc_en='ISO 3166-2 subdivision code.', desc_fr='Code de subdivision ISO 3166-2.'),
        F('Country', en='Country', fr='Pays', fk=COUNTRY),
        F('latitude', 'decimal', en='Latitude', fr='Latitude'), F('longitude', 'decimal', en='Longitude', fr='Longitude'),
        F('validFrom', 'date', en='Valid from', fr='Valide du'), F('validTo', 'date', en='Valid to', fr='Valide au'),
        F('source', en='Source', fr='Source', maxLen=40),
    ], label='${./type} - ${./City} (${./Country})'),
    T('BankAccount', 'Bank accounts', 'Comptes bancaires', ['id'], [
        F('id', 'id', en='Id', fr='Id', min=1), company_fk(),
        F('label', en='Label', fr='Libellé', maxLen=80),
        F('holder', en='Account holder', fr='Titulaire', maxLen=120), F('bankName', en='Bank name', fr='Banque', maxLen=120),
        F('IBAN', en='IBAN', fr='IBAN', maxLen=34, constraint='com.tibco.ebx.ps.fasttrack.companies.constraints.IBANConstraint'),
        F('BIC', en='BIC', fr='BIC', maxLen=11, pattern=r'[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?'),
        F('currency', en='Currency', fr='Devise', fk=CURRENCY),
        F('isDefault', 'boolean', en='Default account?', fr='Compte par défaut ?', default='false'),
        F('status', en='Status', fr='Statut', default='ACTIVE', enum=[('ACTIVE', 'Active', 'Actif'), ('CLOSED', 'Closed', 'Clôturé')]),
        F('validFrom', 'date', en='Valid from', fr='Valide du'), F('validTo', 'date', en='Valid to', fr='Valide au'),
        F('source', en='Source', fr='Source', maxLen=40),
    ], label='${./label} ${./IBAN}'),
    T('Contact', 'Contacts', 'Contacts', ['id'], [
        F('id', 'id', en='Id', fr='Id', min=1), company_fk(),
        F('civility', en='Civility', fr='Civilité', enum=[('MR', 'Mr', 'M.'), ('MRS', 'Mrs', 'Mme'), ('MX', 'Mx', 'Mx')]),
        F('firstName', en='First name', fr='Prénom', maxLen=80), F('lastName', en='Last name', fr='Nom', maxLen=80, min=1),
        F('jobTitle', en='Job title', fr='Fonction', maxLen=120),
        F('role', en='Role', fr='Rôle', fk='/root/ReferenceData/ContactRole'),
        F('email', en='Email', fr='Courriel', maxLen=255, pattern=EMAIL), F('phone', en='Phone', fr='Téléphone', maxLen=40),
        F('mobile', en='Mobile', fr='Mobile', maxLen=40),
        F('isPrimary', 'boolean', en='Primary contact?', fr='Contact principal ?', default='false'),
        F('active', 'boolean', en='Active?', fr='Actif ?', default='true'),
    ], label='${./firstName} ${./lastName}'),
    T('CompanyRelationship', 'Company relationships', 'Relations entre sociétés', ['id'], [
        F('id', 'id', en='Id', fr='Id', min=1),
        company_fk('fromCompany', 'Company', 'Société'),
        F('toCompany', en='Related company', fr='Société liée', fk='/root/Companies',
          desc_en='Empty when the related entity is not in the referential; see the related LEI and name.',
          desc_fr="Vide quand l'entité liée n'est pas dans le référentiel ; voir le LEI et le nom liés."),
        F('relatedLei', en='Related LEI', fr='LEI lié', maxLen=20), F('relatedName', en='Related name', fr='Nom lié', maxLen=255),
        F('type', en='Type', fr='Type', fk='/root/ReferenceData/RelationshipType', min=1),
        F('ownershipPercent', 'decimal', en='Ownership (%)', fr='Détention (%)'),
        F('startDate', 'date', en='Start date', fr='Date de début'), F('endDate', 'date', en='End date', fr='Date de fin'),
        F('status', en='Status', fr='Statut', default='ACTIVE', enum=[('ACTIVE', 'Active', 'Active'), ('INACTIVE', 'Inactive', 'Inactive')]),
        F('corroboration', en='Corroboration', fr='Corroboration', maxLen=40),
        F('source', en='Source', fr='Source', maxLen=40),
    ], label='${./type} ${./relatedName}'),
]

# 5. New reference tables under /root/ReferenceData
def code_table(name, en, fr, extra=(), label='${./Label}', label_fr='${./LabelFR}'):
    return T(name, en, fr, ['Code'], [
        F('Code', en='Code', fr='Code', maxLen=40, min=1),
        F('Label', en='Label (EN)', fr='Libellé anglais', maxLen=255),
        F('LabelFR', en='Label (FR)', fr='Libellé français', maxLen=255)] + list(extra), label=label, label_fr=label_fr)

REF_TABLES = [
    code_table('IdentifierType', 'Identifier types', "Types d'identifiant", [
        F('pattern', en='Pattern', fr='Format (regex)', maxLen=120), F('description', en='Description', fr='Description', maxLen=255)]),
    code_table('AddressType', 'Address types', "Types d'adresse"),
    code_table('ContactRole', 'Contact roles', 'Rôles de contact'),
    code_table('RelationshipType', 'Relationship types', 'Types de relation', [
        F('gleifType', en='GLEIF relationship type', fr='Type de relation GLEIF', maxLen=60)]),
    T('NaceCode', 'NACE codes', 'Codes NACE', ['Code'], [
        F('Code', en='Code', fr='Code', maxLen=10, min=1),
        F('Label', en='Label (EN)', fr='Libellé anglais', maxLen=255),
        F('LabelFR', en='Label (FR)', fr='Libellé français', maxLen=255),
        F('Level', 'int', en='Level', fr='Niveau', desc_en='1 section, 2 division, 3 group, 4 class.'),
        F('Parent', en='Parent', fr='Parent', fk='/root/ReferenceData/NaceCode'),
        F('Section', en='Section', fr='Section', fk='/root/ReferenceData/NaceCode'),
    ], label='${./Code} ${./Label}', label_fr='${./Code} ${./LabelFR}'),
    T('LegalForm', 'Legal forms (ISO 20275)', 'Formes juridiques (ISO 20275)', ['Code'], [
        F('Code', en='ELF code', fr='Code ELF', maxLen=4, min=1),
        F('Name', en='Local name', fr='Nom local', maxLen=255),
        F('TransliteratedName', en='Transliterated name', fr='Nom translittéré', maxLen=255),
        F('Abbreviation', en='Abbreviation', fr='Abréviation', maxLen=120),
        F('Country', en='Country', fr='Pays', fk=COUNTRY),
        F('Jurisdiction', en='Jurisdiction', fr='Juridiction', maxLen=120),
        F('Language', en='Language', fr='Langue', maxLen=10),
        F('Status', en='Status', fr='Statut', enum=[('ACTV', 'Active', 'Actif'), ('INAC', 'Inactive', 'Inactif')]),
    ], label='${./Abbreviation} ${./Name} (${./Code})'),
    T('RegistrationAuthority', 'Registration authorities (GLEIF RA list)', "Autorités d'immatriculation (liste RA GLEIF)", ['Code'], [
        F('Code', en='RA code', fr='Code RA', maxLen=10, min=1),
        F('Country', en='Country', fr='Pays', fk=COUNTRY),
        F('Jurisdiction', en='Jurisdiction', fr='Juridiction', maxLen=120),
        F('InternationalName', en='Register name (international)', fr='Nom du registre (international)', maxLen=255),
        F('LocalName', en='Register name (local)', fr='Nom du registre (local)', maxLen=255),
        F('Organisation', en='Organisation', fr='Organisme', maxLen=255),
        F('Website', en='Website', fr='Site web', maxLen=255),
    ], label='${./InternationalName} (${./Code})'),
]


def render():
    add = []
    for f in ADDITIONAL:
        add += field(f, ' ' * 36)
    grp = []
    for f in GROUPS + ASSOCS:
        grp += field(f, ' ' * 28)
    tab = []
    for t in TABLES:
        tab += table(t, ' ' * 16)
    ref = []
    for t in REF_TABLES:
        ref += table(t, ' ' * 28)
    return add, grp, tab, ref


def patch(src):
    if MARKER in src:
        raise SystemExit("already patched (marker found) - nothing done")
    lines = src.split('\n')
    add, grp, tab, ref = render()

    def find(pred, start=0):
        for i in range(start, len(lines)):
            if pred(lines[i]):
                return i
        raise SystemExit("anchor not found")

    # 4. reference tables: before the 3 closing lines that precede TechnicalData
    i = find(lambda l: '<xs:element name="TechnicalData" minOccurs="1" maxOccurs="1">' in l)
    assert lines[i - 1].strip() == '</xs:element>' and lines[i - 2].strip() == '</xs:complexType>' and lines[i - 3].strip() == '</xs:sequence>'
    lines[i - 3:i - 3] = ref
    # 3. root tables: before ReferenceData
    i = find(lambda l: '<xs:element name="ReferenceData" minOccurs="1" maxOccurs="1">' in l)
    lines[i:i] = tab
    # 2. groups + associations: before the Roles association
    i = find(lambda l: '<xs:element name="Roles" type="xs:string" minOccurs="0" maxOccurs="0">' in l)
    lines[i:i] = grp
    # 1. AdditionalInformation: after the isConsolidated element
    i = find(lambda l: 'HasXREFValueFunction' in l)
    j = find(lambda l: l.strip() == '</xs:element>', i)
    lines[j + 1:j + 1] = add
    # marker after the DMA header comment
    lines.insert(2, MARKER)
    return '\n'.join(lines)


if __name__ == '__main__':
    src = open(XSD, encoding='utf-8').read()
    out = patch(src)
    if '--check' in sys.argv:
        print(out)
    else:
        open(XSD, 'w', encoding='utf-8').write(out)
        print("patched", XSD, len(src.split('\n')), '->', len(out.split('\n')), 'lines')
