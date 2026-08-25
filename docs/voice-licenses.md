# Lizenzlage der Piper-Stimmen

Stand: 2026-08-24. Erhoben durch Abruf aller 174 `MODEL_CARD`-Dateien aus
`huggingface.co/rhasspy/piper-voices`. Die `voices.json`, die die App lädt, enthält **keine**
Lizenzangaben — die stehen nur in den MODEL_CARDs.

Relevant, sobald die App Geld kostet. Bei einer kostenlosen App ist der Druck geringer, weil
die Modelle ohnehin nicht mitgeliefert, sondern vom Nutzer direkt bei Hugging Face geladen werden.

## Ergebnis

| Bewertung | Anzahl | Bedeutung |
|---|---:|---|
| SAUBER | 45 | Lizenz erlaubt kommerzielle Nutzung, Herkunft unbelastet |
| HERKUNFT-BELASTET | 63 | Lizenz wäre in Ordnung, das Modell ist aber von lessac, ryan, amy oder LibriTTS-R feinabgestimmt |
| LIZENZ-UNKLAR | 44 | MODEL_CARD verweist nur auf eine URL oder nennt gar nichts |
| LIZENZ-NEIN | 22 | CC BY-NC(-SA), Blizzard oder ausdrücklich nicht kommerziell |

## Die Abstammungsfalle

Entscheidend ist nicht die Lizenzzeile, sondern die Kette darunter:

```
lessac        Blizzard-Lizenz, ausdrücklich nur Forschung
  ├── ryan        CC BY-NC-SA 4.0  → zusätzlich nicht kommerziell, 21 Stimmen hängen daran
  ├── amy         "See URL"        → Lizenz nicht bezifferbar
  ├── libritts_r  CC BY 4.0        → Etikett sauber, Basis nicht
  └── ~60 weitere direkt feinabgestimmte Stimmen, viele davon mit CC0-Etikett
```

`thorsten` (Deutsch) ist das Musterbeispiel: Lizenz CC0, Trainingsvermerk
"Finetuned from U.S. English lessac voice". Ob sich die Blizzard-Beschränkung auf das
feinabgestimmte Modell fortsetzt, ist nicht abschließend geklärt. Die vorherrschende Lesart
im Piper-Projekt ist: ja.

## Folge für die Sprachabdeckung

Nur **19 von 50** Sprachen haben überhaupt eine saubere Stimme.

Mit sauberer Stimme: `ca cs de el en es fa fr it kk lv ne nl no sl sv te uk zh`

Ohne jede saubere Stimme: `ar bg bn cy da eu fi he hi hu hy id is ja ka ko ku lb ml mr pl pt ro ru sk sq sr sw tr ur vi`

## Vollständige Tabelle

| Bewertung | Stimme | Lizenz laut MODEL_CARD | Herkunft |
|---|---|---|---|
| HERKUNFT-BELASTET | `bg_bg_BG_dimitar_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality) |
| HERKUNFT-BELASTET | `ca_ca_ES_upc_ona_medium` | CC BY-SA 3.0 ES | Finetuned from U.S English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `cs_cs_CZ_jirka_low` | CC0 | Finetuned from U.S. English lessac voice (low quality). |
| HERKUNFT-BELASTET | `cs_cs_CZ_jirka_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `cy_cy_GB_bu_tts_medium` | CC-BY | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `da_da_DK_talesyntese_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `de_de_DE_kerstin_low` | CC0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `de_de_DE_thorsten_high` | CC0 | Finetuned from U.S. English lessac voice (high quality). |
| HERKUNFT-BELASTET | `de_de_DE_thorsten_low` | CC0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `de_de_DE_thorsten_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `el_el_GR_rapunzelina_low` | CC0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `en_en_GB_alba_medium` | https://creativecommons.org/licenses/by/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `en_en_GB_aru_medium` | https://creativecommons.org/licenses/by/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `en_en_GB_northern_english_male_medium` | CC-BY-SA 4.0 International | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `en_en_GB_southern_english_female_low` | CC-BY-SA 4.0 International | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `en_en_GB_vctk_medium` | https://creativecommons.org/licenses/by/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `en_en_US_joe_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `en_en_US_kathleen_low` | CC0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `en_en_US_libritts_r_medium` | CC BY 4.0 | Fine-tuned from English lessac medium on train-clean-360. |
| HERKUNFT-BELASTET | `en_en_US_mike_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `en_en_US_reza_ibrahim_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `en_en_US_sam_medium` | https://www.apache.org/licenses/LICENSE-2.0.txt | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `es_es_ES_davefx_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `es_es_ES_mls_10246_low` | CC BY 4.0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `es_es_ES_mls_9972_low` | CC BY 4.0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `es_es_ES_sharvard_medium` | http://creativecommons.org/licenses/by/3.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `eu_eu_ES_antton_medium` | Creative Commons Attribution 4.0 | Finetuned from U.S English amy voice (medium quality). |
| HERKUNFT-BELASTET | `eu_eu_ES_maider_medium` | Creative Commons Attribution 4.0 | Finetuned from U.S English amy voice (medium quality). |
| HERKUNFT-BELASTET | `fa_fa_IR_amir_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `fa_fa_IR_reza_ibrahim_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `fi_fi_FI_harri_low` | CC0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `fi_fi_FI_harri_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `fr_fr_FR_gilles_low` | CC0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `fr_fr_FR_mls_1840_low` | CC BY 4.0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `fr_fr_FR_siwis_low` | CC-BY 4.0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `fr_fr_FR_siwis_medium` | CC-BY 4.0 | Finetuned from U.S. English lessac voice (medium quality) |
| HERKUNFT-BELASTET | `fr_fr_FR_upmc_medium` | http://creativecommons.org/licenses/by-sa/4.0/ | Finetuned from U.S. English lessac voice (medium quality) |
| HERKUNFT-BELASTET | `hu_hu_HU_anna_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `hu_hu_HU_berta_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `hu_hu_HU_imre_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `mr_mr_IN_google_medium` | Attribution-ShareAlike 4.0 International | Finetuned from LibriTTS-R base model. |
| HERKUNFT-BELASTET | `ne_ne_NP_chitwan_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality) |
| HERKUNFT-BELASTET | `ne_ne_NP_google_medium` | CC-BY-SA-4.0 International | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `nl_nl_BE_nathalie_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality) |
| HERKUNFT-BELASTET | `nl_nl_NL_mls_5809_low` | CC BY 4.0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `nl_nl_NL_mls_7432_low` | CC BY 4.0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `nl_nl_NL_pim_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality) |
| HERKUNFT-BELASTET | `nl_nl_NL_ronnie_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality) |
| HERKUNFT-BELASTET | `no_no_NO_talesyntese_medium` | CC0 | Fine-tuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `pl_pl_PL_darkman_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `pl_pl_PL_gosia_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `pl_pl_PL_mc_speech_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `pl_pl_PL_mls_6892_low` | CC BY 4.0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `pt_pt_BR_cadu_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `pt_pt_BR_edresson_low` | CC BY 4.0 | Finetuned from U.S. English Ryan voice (low quality). |
| HERKUNFT-BELASTET | `pt_pt_BR_faber_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `pt_pt_BR_jeff_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `ro_ro_RO_mihai_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `ru_ru_RU_denis_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `ru_ru_RU_dmitri_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `sk_sk_SK_lili_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `sq_sq_AL_edon_medium` | CC0 | Finetuned from U.S. English lessac voice (medium quality). |
| HERKUNFT-BELASTET | `vi_vi_VN_vais1000_medium` | https://creativecommons.org/licenses/by/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `de_de_DE_pavoque_low` | http://creativecommons.org/licenses/by-nc-sa/4.0/ | Finetuned from U.S. English Ryan voice (low quality). |
| LIZENZ-NEIN | `el_el_GR_joy_medium` | CC BY-NC 4.0 | Trained with piper-train on 3,216 human voice recordings by a native Greek speaker. |
| LIZENZ-NEIN | `en_en_GB_semaine_medium` | http://creativecommons.org/licenses/by-nc-sa/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `en_en_US_hfc_female_medium` | https://creativecommons.org/licenses/by-nc-sa/4.0/deed.en | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `en_en_US_hfc_male_medium` | https://creativecommons.org/licenses/by-nc-sa/4.0/deed.en | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `en_en_US_l2arctic_medium` | https://creativecommons.org/licenses/by-nc/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `en_en_US_lessac_high` | https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html | Trained from scratch. |
| LIZENZ-NEIN | `en_en_US_lessac_low` | https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html | Trained from scratch. |
| LIZENZ-NEIN | `en_en_US_lessac_medium` | https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html | Trained from scratch. |
| LIZENZ-NEIN | `en_en_US_ryan_high` | CC BY-NC-SA 4.0 | Trained from scratch. |
| LIZENZ-NEIN | `en_en_US_ryan_low` | CC BY-NC-SA 4.0 | Trained from scratch. |
| LIZENZ-NEIN | `en_en_US_ryan_medium` | CC BY-NC-SA 4.0 | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `hi_hi_IN_pratham_medium` | http://creativecommons.org/licenses/by-nc-sa/4.0/ | Dataset: https://github.com/AI4Bharat/indicnlp_corpus |
| LIZENZ-NEIN | `hi_hi_IN_priyamvada_medium` | http://creativecommons.org/licenses/by-nc-sa/4.0/ | Dataset: https://github.com/AI4Bharat/indicnlp_corpus |
| LIZENZ-NEIN | `ja_ja_JA_hi_fi_captain_medium` | https://creativecommons.org/licenses/by-nc-sa/4.0/deed.en | Finetuned from LibriTTS-R base model. |
| LIZENZ-NEIN | `ko_ko_KR_kss_medium` | https://creativecommons.org/licenses/by-nc-sa/4.0/ | Finetuned from LibriTTS-R base model. |
| LIZENZ-NEIN | `lb_lb_LU_marylux_medium` | http://creativecommons.org/licenses/by-nc-sa/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `ru_ru_RU_ruslan_medium` | https://creativecommons.org/licenses/by-nc-sa/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `sr_sr_RS_serbski_institut_medium` | https://creativecommons.org/licenses/by-nc-sa/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `tr_tr_TR_dfki_medium` | https://creativecommons.org/licenses/by-nc-sa/4.0/ | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-NEIN | `vi_vi_VN_vivos_x_low` | CC BY-NC-SA 4.0 | Trained from scratch. |
| LIZENZ-NEIN | `zh_zh_CN_xiao_ya_medium` | Non-commercial use (see https://www.data-baker.com/data/index/TNtts/) | Trained from scratch |
| LIZENZ-UNKLAR | `ar_ar_JO_kareem_low` | See URL | Finetuned from U.S English lessac voice (low quality). |
| LIZENZ-UNKLAR | `ar_ar_JO_kareem_medium` | See URL | Finetuned from U.S English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `bn_bn_BD_google_medium` | KEINE | Finetuned from LibriTTS-R voice (medium quality) |
| LIZENZ-UNKLAR | `cy_cy_GB_gwryw_gogleddol_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `de_de_DE_eva_k_x_low` | See URL | Trained from scratch. |
| LIZENZ-UNKLAR | `de_de_DE_karlsson_low` | See URL | Finetuned from U.S. English Ryan voice (low quality). |
| LIZENZ-UNKLAR | `de_de_DE_ramona_low` | See URL | Trained from scratch. |
| LIZENZ-UNKLAR | `en_en_GB_alan_low` | See URL | Finetuned from U.S. English Ryan voice (low quality). |
| LIZENZ-UNKLAR | `en_en_GB_alan_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `en_en_GB_jenny_dioco_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `en_en_US_amy_low` | See URL | Finetuned from U.S. English Ryan voice (low quality). |
| LIZENZ-UNKLAR | `en_en_US_amy_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `en_en_US_arctic_medium` | See LICENSE file | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `en_en_US_danny_low` | See URL | Finetuned from U.S. English Ryan voice (low quality). |
| LIZENZ-UNKLAR | `en_en_US_kusal_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `es_es_MX_ald_x_low` | KEINE | This model was trained from scratch using the synthetic dataset generated with the es_MX-ald-medium voice. |
| LIZENZ-UNKLAR | `fa_fa_IR_gyro_medium` | See URL | Finetuned from Persian TTS Male vits dataset (medium quality). |
| LIZENZ-UNKLAR | `fr_fr_FR_tom_medium` | AGPLv3 | See URL |
| LIZENZ-UNKLAR | `he_he_IL_saspeech_medium` | See dataset URL | Finetuned from LibriTTS-R base model. |
| LIZENZ-UNKLAR | `hi_hi_IN_rohan_medium` | https://www.iitm.ac.in/donlab/indictts/downloads/license.pdf | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `hy_hy_AM_gor_medium` | GPL 2.0 | Finetuned from en_US-bryce-medium model. |
| LIZENZ-UNKLAR | `id_id_ID_news_tts_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `is_is_IS_bui_medium` | See URL | Trained from scratch. |
| LIZENZ-UNKLAR | `is_is_IS_salka_medium` | See URL | Trained from scratch. |
| LIZENZ-UNKLAR | `is_is_IS_steinn_medium` | See URL | Trained from scratch. |
| LIZENZ-UNKLAR | `is_is_IS_ugla_medium` | See URL | Trained from scratch. |
| LIZENZ-UNKLAR | `it_it_IT_paola_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `it_it_IT_riccardo_x_low` | See URL | Trained from scratch. |
| LIZENZ-UNKLAR | `ka_ka_GE_natia_medium` | See LICENSE file | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `ku_ku_TR_berfin_renas_medium` | KEINE | ### Training Data |
| LIZENZ-UNKLAR | `ml_ml_IN_arjun_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `ml_ml_IN_meera_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `pl_pl_PL_bass_high` | KEINE | ? |
| LIZENZ-UNKLAR | `pt_pt_PT_tugão_medium` | KEINE | ? |
| LIZENZ-UNKLAR | `ru_ru_RU_irina_medium` | Unknown | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `sv_sv_SE_alma_medium` | KEINE | Trained on the NST Swedish TTS Dataset (Språkbanken, National Library of Norway). The dataset contains high-quality studio recordings of Swedish speech. |
| LIZENZ-UNKLAR | `sv_sv_SE_lisa_medium` | KEINE | Finetuned from Norweigan talesyntese voice (medium quality). |
| LIZENZ-UNKLAR | `sw_sw_CD_lanfrica_medium` | See URL | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `te_te_IN_maya_medium` | www.iitm.ac.in/donlab/indictts/downloads/license.pdf | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `ur_ur_PK_aegis_female_medium` | KEINE | ? |
| LIZENZ-UNKLAR | `ur_ur_PK_fasih_medium` | KEINE | * **Base model:** Hindi (`hi_IN`) medium (Rohan) checkpoint |
| LIZENZ-UNKLAR | `vi_vi_VN_25hours_single_low` | Unknown | Finetuned from U.S. English Ryan voice (low quality). |
| LIZENZ-UNKLAR | `zh_zh_CN_huayan_medium` | Unknown | Finetuned from U.S. English lessac voice (medium quality). |
| LIZENZ-UNKLAR | `zh_zh_CN_huayan_x_low` | Unknown | Trained from scratch. |
| SAUBER | `ca_ca_ES_upc_ona_x_low` | CC BY-SA 3.0 ES | Trained from scratch. |
| SAUBER | `ca_ca_ES_upc_pau_x_low` | CC BY-SA 3.0 ES | Trained from scratch. |
| SAUBER | `cs_cs_CZ_kasandra_medium` | Creative Commons Attribution 4.0 International (CC BY 4.0) | The voice model was trained using my own original recordings, and I own the rights to the dataset. |
| SAUBER | `de_de_DE_mls_medium` | CC-BY 4.0 | Trained from scratch |
| SAUBER | `de_de_DE_thorsten_emotional_medium` | CC0 | Finetuned from German thorsten voice (medium quality). |
| SAUBER | `el_el_GR_rapunzelina_medium` | CC0 | Trained from scratch. |
| SAUBER | `en_en_GB_cori_high` | public domain | See: https://brycebeattie.com/files/tts/ |
| SAUBER | `en_en_GB_cori_medium` | public domain | See: https://brycebeattie.com/files/tts/ |
| SAUBER | `en_en_US_bryce_medium` | public domain | See: https://brycebeattie.com/files/tts/ |
| SAUBER | `en_en_US_john_medium` | public domain | See: https://brycebeattie.com/files/tts/ |
| SAUBER | `en_en_US_kristin_medium` | public domain | See: https://brycebeattie.com/files/tts/ |
| SAUBER | `en_en_US_libritts_high` | CC BY 4.0 | Trained from scratch on train-clean-360. |
| SAUBER | `en_en_US_ljspeech_high` | public domain | See: https://brycebeattie.com/files/tts/ |
| SAUBER | `en_en_US_ljspeech_medium` | public domain | See: https://brycebeattie.com/files/tts/ |
| SAUBER | `en_en_US_norman_medium` | public domain | See: https://brycebeattie.com/files/tts/ |
| SAUBER | `es_es_AR_daniela_high` | Attribution-ShareAlike 4.0 International | Trained by: https://huggingface.co/larcanio/piper-voices |
| SAUBER | `es_es_ES_carlfm_x_low` | Public domain | Trained from scratch. |
| SAUBER | `es_es_MX_ald_medium` | http://unlicense.org | Finetuned from Spanish davefx voice (medium quality). |
| SAUBER | `es_es_MX_claude_high` | apache-2.0 | See URL above |
| SAUBER | `fa_fa_IR_ganji_adabi_medium` | CC0 | Finetuned from fa_IR Ganji voice (medium quality). |
| SAUBER | `fa_fa_IR_ganji_medium` | CC0 | Finetuned from fa_IR Amir voice (medium quality). |
| SAUBER | `fr_fr_FR_mls_medium` | CC-BY 4.0 | Trained from scratch |
| SAUBER | `it_it_IT_serena_high` | CC-BY-4.0 | Trained from scratch. |
| SAUBER | `it_it_IT_serena_medium` | CC-BY-4.0 | Trained from scratch. |
| SAUBER | `kk_kk_KZ_iseke_x_low` | CC-BY-4.0 | Trained from scratch. |
| SAUBER | `kk_kk_KZ_issai_high` | CC-BY-4.0 | Trained from scratch. |
| SAUBER | `kk_kk_KZ_raya_x_low` | CC-BY-4.0 | Trained from scratch. |
| SAUBER | `lv_lv_LV_aivars_medium` | CC0 1.0 Universal | See URL |
| SAUBER | `ne_ne_NP_google_x_low` | CC-BY-SA-4.0 International | Trained from scratch. |
| SAUBER | `nl_nl_BE_nathalie_x_low` | CC0 | Trained from scratch. |
| SAUBER | `nl_nl_BE_rdh_medium` | CC0 1.0 Universal | Trained from scratch. |
| SAUBER | `nl_nl_BE_rdh_x_low` | CC0 1.0 Universal | Trained from scratch. |
| SAUBER | `nl_nl_NL_alex_medium` | CC0 | Finetuned from Dutch rdh voice (medium quality) |
| SAUBER | `nl_nl_NL_mls_medium` | CC-BY 4.0 | Trained from scratch |
| SAUBER | `no_no_NO_nvcc_medium` | CC0 | Trained from scratch. |
| SAUBER | `sl_sl_SI_artur_medium` | https://creativecommons.org/licenses/by/4.0/ | Trained by [@ppisljar](https://github.com/ppisljar) |
| SAUBER | `sv_sv_SE_nst_medium` | CC0 | Trained from scratch by KBLab at The National Library of Sweden. |
| SAUBER | `te_te_IN_padmavathi_medium` | CC-BY-4.0 | Trained by: https://github.com/PravalX |
| SAUBER | `te_te_IN_venkatesh_medium` | CC-BY-4.0 | Trained by: https://github.com/PravalX |
| SAUBER | `uk_uk_UA_lada_x_low` | Apache 2.0 | Trained from scratch. |
| SAUBER | `uk_uk_UA_mykyta_high` | Apache 2.0 | See: https://huggingface.co/RomanStasyshyn/uk_UA-mykyta-high |
| SAUBER | `uk_uk_UA_oleksa_high` | Apache 2.0 | See: https://huggingface.co/RomanStasyshyn/uk_UA-oleksa-high |
| SAUBER | `uk_uk_UA_tetiana_high` | Apache 2.0 | See: https://huggingface.co/RomanStasyshyn/uk_UA-tetiana-high |
| SAUBER | `uk_uk_UA_ukrainian_tts_medium` | CC0 | Trained from scratch. |
| SAUBER | `zh_zh_CN_chaowen_medium` | CC0 | Finetuned from Xiao Ya voice (medium quality) |

## Vorbehalte

Die Einstufung ist maschinell aus den MODEL_CARDs abgeleitet und keine Rechtsprüfung.
Die 44 unklaren Fälle verweisen auf externe URLs, die einzeln gelesen werden müssten.
`CC-BY` und `CC-BY-SA` sind hier als kommerziell nutzbar eingestuft, verlangen aber
Namensnennung, ShareAlike zusätzlich die Weitergabe unter gleichen Bedingungen.
