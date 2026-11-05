# Guide d'utilisation

**Langues :** [English](USAGE.md) · [日本語](USAGE.ja.md) · [简体中文](USAGE.zh-Hans.md) · [Español](USAGE.es.md) · [हिन्दी](USAGE.hi.md) · [العربية](USAGE.ar.md) · [한국어](USAGE.ko.md) · [Русский](USAGE.ru.md) · Français

Cette application fait tourner un **nœud complet non validateur** du Quantus Network (réseau principal QTC) sur un téléphone Android. Il synchronise la chaîne, vérifie lui-même chaque bloc et relaie les données aux pairs. Il **ne** mine pas, et il n'a **pas de portefeuille** — il ne touche jamais aux phrases de récupération ni aux clés de récompense.

> État : v0.1.5 publiée (2026-11-06), la version actuelle. L'APK signé est disponible sur [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) avec sa somme de contrôle SHA-256. Les versions de test précédentes étaient partagées directement par le mainteneur.

## Prérequis

- Android 12 ou plus récent, appareil arm64
- Espace libre : la base de données du nœud est élaguée (seuls les 256 derniers blocs sont conservés), donc elle reste petite. Mesuré sur un Pixel 9a après une synchronisation complète (2026-11-05) : 1,30 Go au total (96 Mo d'application + 1,20 Go de données du nœud). À titre indicatif, gardez au moins environ 3 Go libres
- Une connexion internet. Le Wi-Fi est fortement recommandé pour la première synchronisation (voir ci-dessous)

Vérifié jusqu'ici : **Pixel 9a sous GrapheneOS**. Les autres appareils devraient fonctionner, mais ils ne sont pas vérifiés. Si vous l'essayez sur un autre modèle, signalez le résultat (que cela fonctionne ou non) dans une Issue GitHub — la liste des appareils vérifiés grandit grâce à ces rapports.

## Installation

1. Récupérez le fichier APK sur [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) (vérifiez la somme SHA-256 publiée avec la version avant d'installer).
2. Ouvrez l'APK avec votre application Fichiers et autorisez l'installation depuis cette source quand Android le demande.
3. Installez, puis ouvrez **Quantus Node**.

## Premier lancement et synchronisation initiale

1. (Facultatif) Réglez d'abord le **nom d'affichage** et l'option de **télémétrie** (voir Réglages). Les changements de réglages nécessitent un redémarrage du nœud (Arrêter → Démarrer) pour s'appliquer.
2. Touchez **Démarrer le nœud**.
3. L'état affiche **Synchronisation** et le meilleur bloc grimpe vers le bloc le plus haut. Sur un Pixel 9a, la première synchronisation depuis la genèse avance à environ 50 blocs par seconde — comptez de l'ordre de **2 heures**. Une fois à jour, un nouveau bloc arrive environ toutes les 12 secondes et l'état passe à synchronisé.
4. Pendant la première synchronisation, gardez si possible le téléphone **en charge et en Wi-Fi**. La synchronisation en données mobiles fonctionne, mais elle télécharge beaucoup de données et consomme sensiblement plus de batterie (mesuré : environ 11 % en 30 minutes en synchronisation sur données mobiles). Une fois la synchronisation terminée, la charge permanente est faible.

Pas besoin de garder l'application ouverte. Le nœud fonctionne comme un service de premier plan : il continue écran éteint ou pendant que vous utilisez d'autres applications, et affiche une notification permanente pendant son fonctionnement. Si vous l'arrêtez puis le redémarrez, la synchronisation reprend où elle s'était arrêtée — la chaîne téléchargée reste sur l'appareil.

## Ce que montre l'écran

- **État** — Synchronisation / synchronisé, avec une ondulation discrète sur la vue pulsée à chaque nouveau meilleur bloc
- **Meilleur bloc / Bloc le plus haut** — où en est votre nœud par rapport à la pointe du réseau qu'il connaît
- **Pairs** — à combien de nœuds vous êtes connecté
- **Traces** — votre journal local : temps de fonctionnement total, nouveaux blocs observés et maximum de pairs observés. Stocké uniquement sur cet appareil ; jamais envoyé, et sans aucune valeur monétaire — c'est simplement la trace de votre contribution
- **Veille quantique** — l'état de synchronisation de votre nœud et la santé de sa connexion au réseau (les informations externes sur les menaces ne sont pas encore implémentées)

## Réglages

- **Apparaître dans la télémétrie** (désactivé par défaut) — quand c'est activé, le nom de votre nœud et son état en direct deviennent publiquement visibles dans la télémétrie officielle sur telemetry.quantus.com. Ne l'activez que si vous voulez figurer dans la liste. S'applique au prochain démarrage du nœud (Arrêter → Démarrer).
- **Nom d'affichage** — le nom qu'utilise votre nœud (également affiché dans la télémétrie si elle est activée). Laissez vide pour utiliser le nom par défaut `quantus-android-node`. S'applique au prochain démarrage du nœud.
- **Langue** — suivre le réglage de l'appareil, ou choisir parmi 9 langues (japonais, anglais, chinois simplifié, espagnol, hindi, arabe, coréen, russe, français).
- **Partager le journal** — partage le journal du nœud en texte. C'est ce qu'il faut joindre quand vous signalez un problème. Avant de coller un journal dans un lieu public, vérifiez d'abord son contenu : il peut inclure l'adresse IP publique de votre nœud et des informations sur les pairs.
- **Verrouiller les réglages** — verrouille les trois réglages ci-dessus (télémétrie, nom d'affichage, langue) pour qu'ils ne changent pas par accident pendant que le téléphone reste à faire tourner le nœud. C'est uniquement une protection contre les manipulations involontaires : il n'y a pas de code PIN, et le démarrage ou l'arrêt du nœud ne sont jamais verrouillés. Désactivez **Verrouiller les réglages** pour pouvoir les modifier à nouveau.

Les réglages ne redémarrent jamais le nœud d'eux-mêmes. Si vous changez quelque chose pendant que le nœud tourne, l'écran vous indique qu'un redémarrage (Arrêter → Démarrer) est nécessaire.

## Au quotidien

- Le nœud ne s'arrête que lorsque vous touchez **Arrêter le nœud**, forcez l'arrêt de l'application, ou redémarrez le téléphone.
- Après un redémarrage du téléphone, ouvrez l'application et touchez à nouveau **Démarrer le nœud** — le démarrage automatique après l'allumage n'est pas encore implémenté.
- Sur certaines versions d'Android de fabricants (Samsung, Xiaomi, etc.), les économies de batterie agressives peuvent arrêter les services d'arrière-plan. Si votre nœud s'arrête tout seul, exemptez cette application de l'optimisation de la batterie dans les réglages du système.
- Un téléphone de rechange branché 24 h/24 ? Vérifiez la batterie de temps en temps — les vieilles batteries lithium peuvent gonfler. Gardez le téléphone dans un endroit ventilé, idéalement avec une limite de charge si votre appareil en propose une.

## En cas de problème

- **Le nœud se ferme tout de suite** — touchez **Partager le journal** dans les Réglages et envoyez le journal avec une Issue GitHub (modèle de rapport de bug). Le journal montre chaque tentative de démarrage et la raison de sa fin.
- **Les pairs restent à 0** — vérifiez la connexion, puis Arrêter → Démarrer une fois.
- **Votre nœud n'apparaît pas dans la télémétrie** — vérifiez que l'option de télémétrie est activée et que le nœud a été redémarré après l'activation. (L'apparition dans la liste est vérifiée : le nœud du mainteneur lui-même figure dans la liste officielle.)
- **Questions de stockage** — la base de données est élaguée aux 256 derniers blocs par conception. Désinstaller l'application supprime la base de données et la clé d'identité du nœud ; une réinstallation repart pour une nouvelle synchronisation avec une nouvelle identité.

Questions, rapports d'appareils et idées sont bienvenus en Issues GitHub. Pour les problèmes de sécurité, suivez [SECURITY.md](../SECURITY.md) plutôt qu'une Issue publique.
