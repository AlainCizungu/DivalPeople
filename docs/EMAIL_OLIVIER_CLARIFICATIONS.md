# E-mail — Olivier (AJF, Kinshasa)

**Objet : DIP / TIX — points à clarifier avant d'aller plus loin**

---

Bonjour Olivier,

Voici les points sur lesquels j'ai besoin de ton avis. Les premiers relèvent du droit et te sont
adressés ; les seconds portent sur les fichiers eux-mêmes et peuvent être transmis tels quels à
Vodacom et à Orange.

## I. Questions juridiques

1. **Base légale (article 192).** À ma lecture, l'Ordonnance-loi 23/010 n'ouvre que deux fondements
   pour un traitement de ce type : le consentement de la personne concernée, ou une obligation
   légale pesant sur le responsable de traitement. L'intérêt légitime — sur lequel reposent la
   plupart des centrales de risques ailleurs — n'y figure pas. Confirmes-tu cette lecture ?

2. **Si c'est le consentement :** à quel moment est-il recueilli, sous quelle forme, et que se
   passe-t-il lorsqu'il est retiré alors que la créance reste impayée ? Un consentement qu'on ne
   peut pas retirer sans conséquence n'en est pas un, et c'est précisément le reproche que
   l'Autorité pourrait nous adresser.

3. **Si c'est l'obligation légale :** quel texte oblige un opérateur télécom à déclarer ses
   impayés ? S'il n'en existe pas aujourd'hui, faut-il passer par une décision de l'ARPTC, un
   arrêté, ou un autre véhicule — et dans quel délai réaliste ?

4. **Personnes morales.** Les deux fichiers dont nous disposons ne contiennent que des entreprises.
   Le Code du numérique protège-t-il les personnes morales au même titre que les personnes
   physiques, ou le régime est-il différent ? La réponse change beaucoup de choses : une centrale
   limitée aux entreprises serait nettement plus simple à lancer.

5. **Durées de conservation.** Nous appliquons trois ans à compter de l'exigibilité, cinq ans en cas
   de récidive. Ces durées viennent des termes de référence, pas d'un texte. Sont-elles fondées
   juridiquement, ou faut-il les revoir ?

6. **Délais de réponse aux personnes concernées.** Nous avons retenu soixante jours pour une demande
   d'accès (article 210) et trente jours pour les autres demandes. Peux-tu confirmer ?

7. **Article 214.** Nous avons compris que la rectification d'une donnée inexacte doit être notifiée
   à ceux qui l'ont reçue. Notre implémentation prévient chaque institution ayant consulté le
   dossier avant la correction. Est-ce la bonne portée, ou l'obligation est-elle plus large ou plus
   étroite ?

8. **Autorité de protection des données.** Est-elle effectivement constituée et opérationnelle ?
   Une déclaration préalable, un enregistrement ou une autorisation sont-ils exigés avant tout
   traitement — y compris pour un pilote à périmètre restreint ?

9. **Secret professionnel et concurrence.** Un opérateur peut-il légalement communiquer à un
   concurrent le fait qu'un de ses clients est en défaut ? Nous ne transmettons ni le montant, ni
   l'identité de l'opérateur déclarant, uniquement le nombre d'institutions concernées — mais je
   préfère que tu valides ce point plutôt que de le supposer acquis.

10. **Contrat d'abonnement.** Que doit contenir le contrat client d'un opérateur pour que la
    déclaration soit régulière ? Si une clause type est nécessaire, mieux vaut la préparer
    maintenant que la découvrir au moment du pilote.

## II. Questions sur les fichiers — à transmettre à Vodacom et à Orange

Les deux exports s'importent désormais correctement dans la plateforme. Les questions ci-dessous
portent sur ce que les colonnes signifient, ce que nous ne pouvons pas deviner sans eux.

### Les deux opérateurs

1. **Devise.** Aucun des deux fichiers ne précise la devise de la colonne `Balance`. Nous avons
   supposé l'USD. Si l'un des deux est en francs congolais, les totaux et le seuil de déclaration
   sont faux d'un facteur d'environ 2 800. **C'est la question la plus importante de cette liste.**

2. **Date d'arrêté.** À quelle date chaque export est-il arrêté ? Les fichiers ne contiennent aucune
   date, et c'est elle qui détermine le point de départ du délai de conservation.

3. **Identifiant national.** C'est le point qui décide de l'utilité de l'échange. Vodacom identifie
   ses clients par sa propre référence de compte, Orange par la seule raison sociale. Rien dans l'un
   des fichiers ne permet d'affirmer qu'une entreprise figurant chez l'un est celle qui figure chez
   l'autre — donc, en l'état, aucun rapprochement n'est possible entre les deux portefeuilles.
   Peuvent-ils ajouter le **RCCM** ou le **numéro impôt** à côté de ce qu'ils envoient déjà ? Même
   partiellement renseigné, cela change la nature du produit.

4. **Balance âgée.** Les tranches d'antériorité s'additionnent-elles pour former la `Balance`, ou la
   `Balance` est-elle une donnée indépendante ? Nous avons retenu la seconde hypothèse.

5. **Relance contractuelle.** La procédure de relance a-t-elle bien été menée pour l'ensemble de ces
   comptes ? La plateforme exige cette attestation avant toute déclaration et l'enregistre au nom de
   la personne qui la donne.

6. **Seuil de déclaration.** Nous appliquons un plancher de 100 USD, en dessous duquel rien n'entre
   dans le registre. Ce seuil leur paraît-il juste au regard de leur portefeuille réel ?

7. **Nature des débiteurs.** Nous avons traité toutes les lignes comme des entreprises. Y a-t-il des
   particuliers dans ces fichiers ? Le régime juridique n'est pas le même.

### Vodacom

8. **`Status A` indique « Write off » sur les 4 290 lignes.** S'agit-il d'un passage en perte
   comptable, ou d'un abandon de créance ? Autrement dit : la créance reste-t-elle due par le
   client ? Une créance abandonnée qui continuerait d'être déclarée serait difficile à défendre.

9. **Soldes créditeurs.** Quelques lignes portent un montant négatif — le client est en avoir. Nous
   les refusons, en le signalant plutôt qu'en les supprimant silencieusement. Est-ce le bon
   traitement ?

10. **Colonnes `PWC`, `Descoped`, `Vaccounts`.** À quoi correspondent-elles ? `Vaccounts` vaut 1 sur
    toutes les lignes.

11. **Homonymes.** L'analyse du fichier fait apparaître 48 raisons sociales portées par plusieurs
    comptes distincts à l'intérieur de leur propre portefeuille. S'agit-il d'un même client avec
    plusieurs comptes, ou d'entreprises différentes ?

### Orange

12. **Colonnes sans intitulé.** Trois colonnes n'ont pas d'en-tête. L'une est vide de bout en bout.
    Les deux autres contiennent des données : l'une porte « ok » ou « write off » sur neuf lignes,
    l'autre une note isolée. Que signifient-elles ?

13. **Colonne `0`.** Elle contient la raison sociale du client mais son en-tête est « 0 ». Est-ce
    une erreur d'export ? Et la colonne `#` est-elle bien un simple numéro de ligne ?

14. **Colonnes `GSM` et `No GSM`.** Elles contiennent « x », « 20 » ou « 21 » plutôt que des numéros
    de téléphone. À quoi renvoient ces valeurs ?

15. **Référence de compte.** Leur système de facturation dispose forcément d'un numéro de compte
    client ; l'export ne le porte pas. Peuvent-ils l'inclure ? Sans lui, deux livraisons successives
    ne peuvent être rapprochées que par la raison sociale, ce qui est la manière la plus fragile
    d'identifier une entreprise.

---

Les points I.1 à I.3 sont bloquants : tant que la base légale n'est pas arrêtée, aucune date de
lancement ne peut être annoncée sérieusement. Le point II.1 l'est tout autant sur le plan technique,
et devrait pouvoir se régler en un appel.

Merci d'avance,

Alain
