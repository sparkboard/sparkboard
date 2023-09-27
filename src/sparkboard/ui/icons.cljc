(ns sparkboard.ui.icons
  (:require [yawn.view :as v]))

(defn arrow-back [& [class-name]]
  (v/x
    [:svg {:xmlns     "http://www.w3.org/2000/svg"
           :viewBox   "0 0 20 20"
           :fill      "currentColor"
           :className class-name}
     [:g {:clipPath "url(#clip0_9_2121)"}
      [:path
       {:fillRule "evenodd"
        :d
        "M10 18a8 8 0 100-16 8 8 0 000 16zm3.25-7.25a.75.75 0 000-1.5H8.66l2.1-1.95a.75.75 0 10-1.02-1.1l-3.5 3.25a.75.75 0 000 1.1l3.5 3.25a.75.75 0 001.02-1.1l-2.1-1.95h4.59z"
        :clipRule "evenodd"}]]
     [:defs [:clipPath#clip0_9_2121 [:path {:d "M0 0h20v20H0z"}]]]]))

(defn settings [& [class-name]]
  (v/x
    [:svg {:class (or class-name "w-6 h-6") :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24" :fill "currentColor"}
     [:path {:fillRule "evenodd" :d "M11.828 2.25c-.916 0-1.699.663-1.85 1.567l-.091.549a.798.798 0 01-.517.608 7.45 7.45 0 00-.478.198.798.798 0 01-.796-.064l-.453-.324a1.875 1.875 0 00-2.416.2l-.243.243a1.875 1.875 0 00-.2 2.416l.324.453a.798.798 0 01.064.796 7.448 7.448 0 00-.198.478.798.798 0 01-.608.517l-.55.092a1.875 1.875 0 00-1.566 1.849v.344c0 .916.663 1.699 1.567 1.85l.549.091c.281.047.508.25.608.517.06.162.127.321.198.478a.798.798 0 01-.064.796l-.324.453a1.875 1.875 0 00.2 2.416l.243.243c.648.648 1.67.733 2.416.2l.453-.324a.798.798 0 01.796-.064c.157.071.316.137.478.198.267.1.47.327.517.608l.092.55c.15.903.932 1.566 1.849 1.566h.344c.916 0 1.699-.663 1.85-1.567l.091-.549a.798.798 0 01.517-.608 7.52 7.52 0 00.478-.198.798.798 0 01.796.064l.453.324a1.875 1.875 0 002.416-.2l.243-.243c.648-.648.733-1.67.2-2.416l-.324-.453a.798.798 0 01-.064-.796c.071-.157.137-.316.198-.478.1-.267.327-.47.608-.517l.55-.091a1.875 1.875 0 001.566-1.85v-.344c0-.916-.663-1.699-1.567-1.85l-.549-.091a.798.798 0 01-.608-.517 7.507 7.507 0 00-.198-.478.798.798 0 01.064-.796l.324-.453a1.875 1.875 0 00-.2-2.416l-.243-.243a1.875 1.875 0 00-2.416-.2l-.453.324a.798.798 0 01-.796.064 7.462 7.462 0 00-.478-.198.798.798 0 01-.517-.608l-.091-.55a1.875 1.875 0 00-1.85-1.566h-.344zM12 15.75a3.75 3.75 0 100-7.5 3.75 3.75 0 000 7.5z" :clipRule "evenodd"}]]))

(defn loading [& [class]]
  (v/x
    [:svg {:xmlns   "http://www.w3.org/2000/svg"
           :viewBox "0 0 20 20"
           :fill    "currentColor"
           :class   ["rotate-3s" class]}
     [:path
      {:fillRule "evenodd"
       :d        "M15.312 11.424a5.5 5.5 0 01-9.201 2.466l-.312-.311h2.433a.75.75 0 000-1.5H3.989a.75.75 0 00-.75.75v4.242a.75.75 0 001.5 0v-2.43l.31.31a7 7 0 0011.712-3.138.75.75 0 00-1.449-.39zm1.23-3.723a.75.75 0 00.219-.53V2.929a.75.75 0 00-1.5 0V5.36l-.31-.31A7 7 0 003.239 8.188a.75.75 0 101.448.389A5.5 5.5 0 0113.89 6.11l.311.31h-2.432a.75.75 0 000 1.5h4.243a.75.75 0 00.53-.219z"
       :clipRule "evenodd"}]]))

(defn search [classes]
  (v/x
    [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :class classes}
     [:path {:fillRule "evenodd" :d "M9 3.5a5.5 5.5 0 100 11 5.5 5.5 0 000-11zM2 9a7 7 0 1112.452 4.391l3.328 3.329a.75.75 0 11-1.06 1.06l-3.329-3.328A7 7 0 012 9z" :clipRule "evenodd"}]]))

(defn languages [& [classes]]
  (v/x
    [:svg {:xmlns     "http://www.w3.org/2000/svg"
           :viewBox   "0 0 20 20"
           :fill      "currentColor"
           :className classes}
     [:path
      {:d
       "M7.75 2.75a.75.75 0 00-1.5 0v1.258a32.987 32.987 0 00-3.599.278.75.75 0 10.198 1.487A31.545 31.545 0 018.7 5.545 19.381 19.381 0 017 9.56a19.418 19.418 0 01-1.002-2.05.75.75 0 00-1.384.577 20.935 20.935 0 001.492 2.91 19.613 19.613 0 01-3.828 4.154.75.75 0 10.945 1.164A21.116 21.116 0 007 12.331c.095.132.192.262.29.391a.75.75 0 001.194-.91c-.204-.266-.4-.538-.59-.815a20.888 20.888 0 002.333-5.332c.31.031.618.068.924.108a.75.75 0 00.198-1.487 32.832 32.832 0 00-3.599-.278V2.75z"}]
     [:path
      {:fillRule "evenodd"
       :d
       "M13 8a.75.75 0 01.671.415l4.25 8.5a.75.75 0 11-1.342.67L15.787 16h-5.573l-.793 1.585a.75.75 0 11-1.342-.67l4.25-8.5A.75.75 0 0113 8zm2.037 6.5L13 10.427 10.964 14.5h4.073z"
       :clipRule "evenodd"}]]))

(defn checkmark [& [classes]]
  (v/x
    [:svg {:class classes :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
     [:path {:fill-rule "evenodd" :d "M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z" :clip-rule "evenodd"}]]))

(defn chevron-down [& [classes]]
  (v/x
    [:svg {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :strokeWidth "{1.5}" :stroke "currentColor" :class classes}
     [:path {:strokeLinecap "round" :strokeLinejoin "round" :d "M19.5 8.25l-7.5 7.5-7.5-7.5"}]]))

(defn chevron-down-mini [& [classes]]
  (v/x
    [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :class classes}
     [:path {:fillRule "evenodd" :d "M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" :clipRule "evenodd"}]]))

(defn chevron-up [& [classes]]
  (v/x
    [:svg {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :strokeWidth "{1.5}" :stroke "currentColor" :class classes}
     [:path {:strokeLinecap "round" :strokeLinejoin "round" :d "M4.5 15.75l7.5-7.5 7.5 7.5"}]]))

(defn close [& [classes]]
  (v/x
    [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :class classes}
     [:path {:d "M6.28 5.22a.75.75 0 00-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 101.06 1.06L10 11.06l3.72 3.72a.75.75 0 101.06-1.06L11.06 10l3.72-3.72a.75.75 0 00-1.06-1.06L10 8.94 6.28 5.22z"}]]))