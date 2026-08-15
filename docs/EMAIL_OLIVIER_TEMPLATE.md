# E-mail — Olivier (AJF, Kinshasa)

**Objet : DIP — modèle de fichier pour les opérateurs, et quatre points restés ouverts**

**Pièce jointe : `MODELE_IMPORT_DIP.xlsx`**

---

Bonjour Olivier,

Merci pour tes réponses, qui ont débloqué plusieurs choses d'un coup. La devise en particulier :
l'encours est désormais pris en compte dans l'évaluation du risque, ce qu'il ne pouvait pas être
tant que personne ne confirmait en quoi les montants étaient libellés.

Tu trouveras en pièce jointe le modèle de tableau que tu demandais, prêt à être transmis à Vodacom
et à Orange. Il comporte trois onglets : le tableau à remplir avec deux lignes d'exemple, une notice
colonne par colonne, et la liste de ce que la plateforme refuse — avec, dans chaque cas, si le refus
coûte une ligne ou le fichier entier.

Trois précisions sur ce modèle :

- **Le secteur d'activité et l'adresse d'exploitation y figurent**, comme tu l'avais recommandé.
  Aucune livraison actuelle ne les porte, et ce sont exactement les deux informations qui manquent
  pour distinguer deux entreprises homonymes d'une seule entreprise réenregistrée.
- **Six colonnes sont demandées mais pas encore exploitées.** La notice le dit explicitement, en
  toutes lettres, plutôt que de les faire remplir en silence. Elles figurent quand même dans le
  modèle : un opérateur modifie son export une fois, et le lui demander deux fois est le meilleur
  moyen de perdre sa bonne volonté.
- **Aucune colonne de tranche d'ancienneté n'est demandée** — ni « 30 jours », ni « 360 + jours ».
  Le modèle demande le montant dû et la date d'exigibilité, et la plateforme calcule l'ancienneté
  elle-même. Cela règle une ambiguïté plutôt que de la déplacer, comme tu le verras au point 3
  ci-dessous.

---

## Quatre points restés ouverts

**1. Le retrait du consentement.** C'est le plus important des quatre. Tes réponses établissent que
le fondement est contractuel — les opérateurs et les banques disposent déjà de clauses les
autorisant à utiliser les données de leurs clients — et que rien n'oblige légalement un opérateur à
déclarer ses impayés.

Reste alors la question que je n'ai pas encore posée assez clairement : **que se passe-t-il lorsque
la personne retire son consentement alors que la créance est toujours impayée ?**

La plateforme n'a aujourd'hui aucun mécanisme de retrait. L'effacement existe et il est refusé
lorsque la dette reste due — ce qui se défend sans difficulté sous le régime d'une obligation
légale, et bien moins facilement sous celui du consentement. Ta réponse décide si ce refus tient.

**2. Cinq ans : pour les deux durées, ou une seule ?** Nous appliquions trois ans pour un premier
défaut et cinq en cas de récidive. Tu as répondu cinq ans, et nous avons donc mis les deux durées à
cinq — ce qui, en pratique, revient à ne plus faire de différence entre un premier défaut et une
récidive. Est-ce l'intention, ou faut-il conserver un écart entre les deux ?

**3. La ligne « + 360 jours ».** Tu indiques de retenir cette ligne. Deux lectures possibles, et
l'écart entre elles est considérable :

- soit il s'agit de sélectionner les créances dont l'ancienneté dépasse 360 jours, le montant à
  déclarer restant le solde total ;
- soit le montant à déclarer est celui de la tranche « + 360 jours » elle-même, et non le solde.

Dans le second cas, **tous les montants déjà importés sont erronés** — et ils comptent désormais
dans l'évaluation du risque. Peux-tu trancher avant le prochain import ? Pour les fichiers reçus au
nouveau format la question ne se posera plus, mais elle reste entière pour les 4 290 lignes déjà en
base.

**4. Les relances.** La plateforme exige, pour chaque ligne, l'attestation que la procédure
contractuelle de relance a bien été suivie, et elle enregistre qui l'a portée. Cette procédure
a-t-elle effectivement été appliquée aux 4 290 comptes du fichier Vodacom ? Si la réponse est
partielle, mieux vaut le savoir maintenant que le découvrir sur contestation.

---

Deux points mineurs, pour mémoire : les colonnes `PWC`, `Descoped` et `Vaccounts` du fichier Vodacom
restent inexpliquées (`Vaccounts` vaut 1 sur chaque ligne), et le même fichier contient 48
homonymes à l'intérieur du seul livre de Vodacom — plusieurs comptes d'un même client, ou des
entreprises distinctes ? Ces questions relèvent de l'opérateur plutôt que de toi, mais elles
peuvent accompagner le modèle.

Bien à toi,

Alain
