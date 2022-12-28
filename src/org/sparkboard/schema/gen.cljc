(ns org.sparkboard.schema.gen)

;; TODO
;; For the text skeleton, include buttons that auto-populate fields (for quickly clicking through
;; forms). Sample data here has been generated using chat.openai.com

(def cities
  #{"Shanghai" "Montreal" "New Orleans" "Sydney" "Hong Kong" "Lisbon" "Chicago" "Cairo" "Stockholm" "Reykjavik" "Madrid" "Beijing" "Vancouver" "Moscow" "Warsaw" "Quito" "Tel Aviv" "Riyadh" "Dubai" "Edinburgh" "Istanbul" "Wellington" "Amsterdam" "Melbourne" "Johannesburg" "Ho Chi Minh City" "Berlin" "Karachi" "San Francisco" "Toronto" "Rio de Janeiro" "Budapest" "The Hague" "Tehran" "Copenhagen" "Buenos Aires" "London" "Miami" "Seoul" "Jakarta" "Vienna" "Mumbai" "Oslo" "Dhaka" "Rome" "Paris" "Ottawa" "Venice" "New York City" "Los Angeles" "Prague" "São Paulo" "St. Petersburg" "Singapore" "Zurich" "Tokyo" "Delhi" "Mexico City" "Abu Dhabi"})

(def boards
  [{:board/title "Code4Care"
    :board.landing-page/description-content "Improve healthcare and public health"}
   {:board/title "EcoTech"
    :board.landing-page/description-content "Reduce global warming and environmental impact"}
   {:board/title "Hack4Change"
    :board.landing-page/description-content "Promote social justice and equality"}
   {:board/title "TechForGood"
    :board.landing-page/description-content "Use technology for positive social impact"}
   {:board/title "SmartCitySolutions"
    :board.landing-page/description-content "Create innovative solutions for urban problems"}
   {:board/title "EdTechInnovate"
    :board.landing-page/description-content "Improve education and access to knowledge"}
   {:board/title "AgTechHack"
    :board.landing-page/description-content "Promote sustainable agriculture and food security"}
   {:board/title "EnergyInnovation"
    :board.landing-page/description-content "Develop clean and renewable energy solutions"}
   {:board/title "WaterWise"
    :board.landing-page/description-content "Address water scarcity and water management challenges"}
   {:board/title "DisasterPrep"
    :board.landing-page/description-content "Prepare for and mitigate natural disasters"}
   {:board/title "SecureTech"
    :board.landing-page/description-content "Improve cyber security and protect against cyber threats"}
   {:board/title "FintechForward"
    :board.landing-page/description-content "Develop financial technology solutions"}
   {:board/title "TransportRevolution"
    :board.landing-page/description-content "Create innovative transportation solutions"}
   {:board/title "HousingHackathon"
    :board.landing-page/description-content "Address housing affordability and homelessness"}
   {:board/title "JournalismTech"
    :board.landing-page/description-content "Improve journalism and the media industry"}
   {:board/title "GovInnovate"
    :board.landing-page/description-content "Innovate in the public sector and government"}
   {:board/title "ArtTech"
    :board.landing-page/description-content "Promote and support the arts"}
   {:board/title "SportsTech"
    :board.landing-page/description-content "Enhance the sports industry and fan experience"}
   {:board/title "FashionForward"
    :board.landing-page/description-content "Promote sustainable fashion and design"}
   {:board/title "MusicTech"
    :board.landing-page/description-content "Advance the music industry and support musicians"}])

(def projects
  [{:project/title "EcoTrace"
    :project/summary-text "An app to help people track and reduce their carbon footprint"}
   {:project/title "VolunteerMatch"
    :project/summary-text "A platform to connect volunteers with non-profit organizations"}
   {:project/title "TrialFinder"
    :project/summary-text "A tool to help people find and sign up for clinical trials"}
   {:project/title "AirWatch"
    :project/summary-text "A system to monitor and improve air quality in cities"}
   {:project/title "FoodRescue"
    :project/summary-text "A platform to facilitate the donation of unused food to food banks"}
   {:project/title "HousingHero"
    :project/summary-text "A tool to help people find affordable housing in their area"}
   {:project/title "FarmConnect"
    :project/summary-text "A platform to connect small farmers with local buyers"}
   {:project/title "PolitiConnect"
    :project/summary-text "A website to help people learn about and get involved in local politics"}
   {:project/title "WaterWise"
    :project/summary-text "An app to help people track and reduce their water usage"}
   {:project/title "AccessibleRide"
    :project/summary-text "A platform to connect people with disabilities with accessible transportation options"}
   {:project/title "AccessCheck"
    :project/summary-text "A system to monitor and improve the accessibility of public spaces"}
   {:project/title "CoastalCleaners"
    :project/summary-text "A tool to help people find and participate in beach clean-up events"}
   {:project/title "ClothingCare"
    :project/summary-text "A platform to facilitate the donation of gently used clothing to those in need"}
   {:project/title "VoteDriver"
    :project/summary-text "A website to help people find and sign up for voter registration drives"}
   {:project/title "EcoHero"
    :project/summary-text "An app to help people learn about and get involved in environmental conservation efforts"}
   {:project/title "LanguageExchange"
    :project/summary-text "A tool to help people find and sign up for language exchange programs"}
   {:project/title "FreelanceHub"
    :project/summary-text "A platform to connect freelancers with work opportunities"}
   {:project/title "SustainableTravel"
    :project/summary-text "A system to help people find and book sustainable travel options"}
   {:project/title "AnimalAllies"
    :project/summary-text "A website to help people learn about and get involved in animal welfare efforts"}
   {:project/title "FoodSavior"
    :project/summary-text "An app to help people track and reduce their food waste"}
   {:project/title "BloodyGood"
    :project/summary-text "A tool to help people find and sign up for blood donation opportunities"}
   {:project/title "BusinessBoost"
    :project/summary-text "A platform to connect small business owners with affordable financial services"}
   {:project/title "FitFinder"
    :project/summary-text "A system to help people find and book fitness classes in their area"}
   {:project/title "MindWell"
    :project/summary-text "A website to help people learn about and get involved in mental health awareness campaigns"}
   {:project/title "MindMate"
    :project/summary-text "An app to help people track and improve their mental health"}
   {:project/title "FurnitureFirst"
    :project/summary-text "A platform to facilitate the donation of gently used furniture to those in need"}
   {:project/title "TalentTrade"
    :project/summary-text "A tool to help people find and sign up for skill-sharing programs"}
   {:project/title "CulturalConnect"
    :project/summary-text "A system to help people find and book cultural events in their area"}
   {:project/title "JusticeNow"
    :project/summary-text "A website to help people learn about and get involved in social justice campaigns"}
   {:project/title "EnergyEfficiency"
    :project/summary-text "An app to help people track and reduce their energy usage"}
   {:project/title "RemoteRevolution"
    :project/summary-text "A platform to connect job seekers with remote work opportunities"}
   {:project/title "JusticeJunction"
    :project/summary-text "A tool to help people find and sign up for pro bono legal services"}
   {:project/title "NatureNavigator"
    :project/summary-text "A system to help people find and book nature-based tourism experiences"}
   {:project/title "FashionForward"
    :project/summary-text "A website to help people learn about and get involved in sustainable fashion campaigns"}
   {:project/title "HealthHero"
    :project/summary-text "An app to help people track and improve their physical health"}
   {:project/title "MusicMasterclass"
    :project/summary-text "A platform to connect aspiring musicians with music industry professionals"}
   {:project/title "MentorMatch"
    :project/summary-text "A tool to help people find and sign up for mentorship programs"}
   {:project/title "ChefCentral"
    :project/summary-text "A system to help people find and book cooking classes in their area"}
   {:project/title "AnimalAdvocates"
    :project/summary-text "A website to help people learn about and get involved in animal rights campaigns"}
   {:project/title "WasteWarrior"
    :project/summary-text "An app to help people track and reduce their household waste"}
   {:project/title "BookBuddies"
    :project/summary-text "A platform to facilitate the donation of gently used books to those in need"}
   {:project/title "LanguageLearning"
    :project/summary-text "A tool to help people find and sign up for language learning classes"}
   {:project/title "ArtExhibit"
    :project/summary-text "A system to help people find and book art exhibitions in their area"}
   {:project/title "EducateEmpower"
    :project/summary-text "A website to help people learn about and get involved in educational reform campaigns"}
   {:project/title "FinanceTracker"
    :project/summary-text "An app to help people track and improve their financial health"}
   {:project/title "MarketingBoost"
    :project/summary-text "A platform to connect small business owners with marketing resources"}
   {:project/title "FinancialLiteracy"
    :project/summary-text "A tool to help people find and sign up for financial literacy classes"}
   {:project/title "TheaterTicket"
    :project/summary-text "A system to help people find and book theater performances in their area"}
   {:project/title "EqualityEmpower"
    :project/summary-text "A website to help people learn about and get involved in disability rights campaigns"}
   {:project/title "EcoEfficiency"
    :project/summary-text "An app to help people track and improve the efficiency of their home appliances"}])

(def people-names ["Eiji Okuda" "Olebogeng Mosweu" "Bukola Olufunmi" "Naida Katic" "Zayd Hassan" "Jacqueline Umutoniwase" "Asante Mbembe" "Joana Diallo" "Ming Li" "Linda Monteiro" "Maurice Nkurunziza" "Gai Kigo" "Gloria Ntakirutimana" "Hae-il Park" "Hanae Kan" "Kai Thompson" "Mia Brown" "Dauda Kone" "Eloise Green" "Hideaki Anno" "Tereza Pavlova" "Sonia Escoda" "Monsieur Verdoux" "Florence Tchokote" "Boitumelo Kgosana" "Agatha Uwimana" "Ibrahim Niyonzima" "Leah Patel" "Amaya Saito" "Han-seon Jung" "Lizzie Bennet" "Dai-zen Su" "Fatouma Zohra" "Bai Ling" "Sherlock Holmes" "Élodie Rousseau" "Lukas Schneider" "Marie-Eve Lacroix" "Caren N'Dour" "Anisa Bajraktari" "Anne Shirley" "Chih-yen Chien" "Dorji Pema" "Ayesha Ali" "Ugyen Dorji" "Javon Walters" "Lemony Snicket" "Jordi Riera" "Esperance Nibizi" "Léa Girard" "Marie-Claire Ndouba" "Baptiste Ndayisenga" "Aisha Bamba" "Nurullah Khalid" "Cheng-tung Ho" "Karim Bouteflika" "Theophile Ntirugirimo" "Pema Wangchuk" "Maria Christodoulou" "Chao Wang" "Claudine Nkodia" "Goro Inagaki" "Alhassan Kamara" "Hafsa Omar" "Hana Kino" "Nargiz Aliyeva" "Binta Jalloh" "Armen Sargsyan" "Dae-seong Jang" "Gaspard Ndayishimiye" "Alessandra Nkrumah" "Armin Halilovic" "Holden Caulfield" "Michael Pantelis" "Juan Hernandez" "Nurse Ratched" "Chika Umino" "Laurent Nshimiyimana" "Chi-lin Hsu" "William Dupuis" "Ivan Kuznetsov" "Ha Ji-won" "Amina Hassanova" "Oliver Twist" "Chantal N'Guessan" "Haruo Nakajima" "Atticus Finch" "Han-mo Koo" "Chi-ling Lin" "Chang-ho Lee" "Karl-Erik Kuusk" "Reka Horvath" "Kiara Joseph" "Albiona Rama" "Jean Claude Harerimana" "Chen-tung Yen" "Armand Bakonga" "Lamia Zeroual" "Fumiko Okamoto" "Thandie Nxumalo" "Elena Demetriou" "Mateo Kovac" "Alex Anderson" "Dorian Gray" "Antoine Dufresne" "Chloé Moreau" "Zayn Khan" "Fatima Benali" "Liisi Mägi" "Haruka Ayase" "Bisi Adewale" "Erion Tahiri" "Efua Tetteh" "Dominique Poulin" "Aigerim Nurlanova" "Narine Khachatryan" "Liam Williams" "Emmanuel Ndahiro" "Ava Jones" "Boakye Mensah" "Isabelle Gagné" "Halima Djanet" "Liliane Niyigena" "Gregor Samsa" "Maxime St-Laurent" "Akiyoshi Nakamura" "Omphemetse Seleka" "Hester Prynne" "Chin-hui Tsai" "Hatsu Hioki" "Big Brother" "Chang-wook Ji" "Darya Ivanova" "Diana Kyeremateng" "Mariam Kipiani" "Bao Nguyen" "Willy Wonka" "Mr. Darcy" "Bilbo Baggins" "Sakura Tanaka" "Ekaterina Mikhailova" "Ekua Owusu" "Elmir Mammadov" "Akiko Kimura" "Grace Ntahobari" "Lebogang Phaleng" "Dr. Jekyll" "Hai-Tao Sun" "Josée Niyitegeka" "Huck Finn" "Olivier Bouchard" "Ani Gasparyan" "Lana Horvat" "Giorgi Chkheidze" "Esther Kabera" "Eduardo dos Santos" "Jasmine Davis" "Thulani Dlamini" "Julie Beauchamp" "Tariq Ahmed" "Muhammad Shah" "Charlotte St-Pierre" "Anna Schmidt" "Mary Evans" "Marc Llovera" "Amirah Adnan" "Julienne Nyirahabineza" "Hussein Al Khalifa" "Anne-Marie Koffi" "Fatimata Yacoub" "Mahamat Saleh" "Alina Kim" "Annie Martineau" "Sofia Paulo" "Akira Ishida" "Nicolas Bernier" "Isabel Neto" "Chen Chang" "Auntie Mame" "Rashida Samuel" "Sophie Mueller" "Thabo Mkhonta" "Sophia Johnson" "Olivia Smith" "Alexis Roy" "Vincent Lefebvre" "Chin-yuan Huang" "Jan Kolar" "Hideki Kamiya" "Abigail Chen" "Henriette Zang" "Isabella Taylor" "Babatunde Fela" "Eiko Koike" "Chin Han" "Dai-yun Teng" "Daulet Nurmagambetov" "Brigitte Ndoumbe" "Holly Golightly" "Tshepang Mokgosi" "Don Quixote" "Étienne Dubois" "Ada Okonkwo" "Miklos Szabo" "Mafalda da Silva"])

























