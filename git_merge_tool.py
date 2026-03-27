#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Git Merge Tool - Ferramenta visual para merge de branches
Autor: Hugo | Gerado com Claude
"""

import tkinter as tk
from tkinter import ttk, messagebox, filedialog, scrolledtext
import subprocess
import threading
import os
import json
from pathlib import Path

# ─── Configuração ───────────────────────────────────────────────────────────
CONFIG_FILE = os.path.join(os.path.expanduser("~"), ".git_merge_tool.json")

DEFAULT_CONFIG = {
    "repo_path": "",
    "profiles": {
        "Homologação": {
            "source_branch": "homologacao_merge",
            "target_branches": [
                "1_Homologação-Miranga",
                "SPRINT_10",
                "SPRINT_01",
                "SPRINT_04"
            ]
        },
        "Produção": {
            "source_branch": "Produção-Miranga",
            "target_branches": [
                "main",
                "release",
                "deploy"
            ]
        }
    },
    "last_profile": "Homologação"
}


# ─── Cores e Estilo ─────────────────────────────────────────────────────────
COLORS = {
    "bg": "#1e1e2e",
    "bg_secondary": "#282840",
    "bg_card": "#313150",
    "accent": "#7c3aed",
    "accent_hover": "#6d28d9",
    "success": "#22c55e",
    "error": "#ef4444",
    "warning": "#f59e0b",
    "text": "#e2e8f0",
    "text_dim": "#94a3b8",
    "text_bright": "#f8fafc",
    "border": "#475569",
    "input_bg": "#1e293b",
    "checkbox_on": "#7c3aed",
    "checkbox_off": "#475569",
}


def load_config():
    """Carrega configuração do arquivo JSON."""
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                config = json.load(f)
                for key in DEFAULT_CONFIG:
                    if key not in config:
                        config[key] = DEFAULT_CONFIG[key]
                return config
        except Exception:
            pass
    return DEFAULT_CONFIG.copy()


def save_config(config):
    """Salva configuração no arquivo JSON."""
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print(f"Erro ao salvar config: {e}")


class GitMergeTool:
    def __init__(self, root):
        self.root = root
        self.root.title("Git Merge Tool")
        self.root.geometry("960x800")
        self.root.configure(bg=COLORS["bg"])
        self.root.minsize(850, 700)

        self.config = load_config()
        self.repo_path = tk.StringVar(value=self.config.get("repo_path", ""))
        self.source_branch = tk.StringVar()
        self.search_var = tk.StringVar()
        self.target_vars = {}         # {branch_name: BooleanVar} — para branches do perfil
        self.all_branch_vars = {}     # {branch_name: BooleanVar} — para TODAS do repo
        self.is_running = False
        self.available_branches = []

        self._setup_styles()
        self._build_ui()

        # Carregar último perfil
        last = self.config.get("last_profile", "")
        if last and last in self.config.get("profiles", {}):
            self.profile_combo.set(last)
            self._load_profile()

        # Se já tem repo, carregar branches
        if self.repo_path.get() and os.path.isdir(self.repo_path.get()):
            self._refresh_branches()

    def _setup_styles(self):
        """Configura estilos ttk."""
        style = ttk.Style()
        style.theme_use("clam")

        style.configure("Dark.TFrame", background=COLORS["bg"])
        style.configure("Card.TFrame", background=COLORS["bg_card"])
        style.configure("Dark.TLabel",
                        background=COLORS["bg"],
                        foreground=COLORS["text"],
                        font=("Segoe UI", 10))
        style.configure("Title.TLabel",
                        background=COLORS["bg"],
                        foreground=COLORS["text_bright"],
                        font=("Segoe UI", 16, "bold"))
        style.configure("Subtitle.TLabel",
                        background=COLORS["bg"],
                        foreground=COLORS["text_dim"],
                        font=("Segoe UI", 9))
        style.configure("Card.TLabel",
                        background=COLORS["bg_card"],
                        foreground=COLORS["text"],
                        font=("Segoe UI", 10))
        style.configure("CardTitle.TLabel",
                        background=COLORS["bg_card"],
                        foreground=COLORS["text_bright"],
                        font=("Segoe UI", 11, "bold"))
        style.configure("Search.TLabel",
                        background=COLORS["bg_card"],
                        foreground=COLORS["text_dim"],
                        font=("Segoe UI", 9))

        # Botões
        style.configure("Accent.TButton",
                        background=COLORS["accent"],
                        foreground="white",
                        font=("Segoe UI", 10, "bold"),
                        padding=(16, 8))
        style.map("Accent.TButton",
                  background=[("active", COLORS["accent_hover"]),
                              ("disabled", COLORS["border"])])

        style.configure("Secondary.TButton",
                        background=COLORS["bg_secondary"],
                        foreground=COLORS["text"],
                        font=("Segoe UI", 9),
                        padding=(10, 5))
        style.map("Secondary.TButton",
                  background=[("active", COLORS["bg_card"])])

        # Combobox
        style.configure("Dark.TCombobox",
                        fieldbackground=COLORS["input_bg"],
                        background=COLORS["bg_secondary"],
                        foreground=COLORS["text"],
                        selectbackground=COLORS["accent"],
                        padding=6)

    def _build_ui(self):
        """Constrói a interface."""
        # Container principal com scroll
        main = ttk.Frame(self.root, style="Dark.TFrame", padding=20)
        main.pack(fill=tk.BOTH, expand=True)

        # ─── Header ───
        header = ttk.Frame(main, style="Dark.TFrame")
        header.pack(fill=tk.X, pady=(0, 12))

        ttk.Label(header, text="Git Merge Tool",
                  style="Title.TLabel").pack(side=tk.LEFT)
        ttk.Label(header, text="Merge multiplas branches com facilidade",
                  style="Subtitle.TLabel").pack(side=tk.LEFT, padx=(15, 0), pady=(5, 0))

        # ─── Repositório ───
        repo_frame = self._card(main, "Repositorio Git")
        repo_inner = ttk.Frame(repo_frame, style="Card.TFrame")
        repo_inner.pack(fill=tk.X, padx=10, pady=(0, 10))

        repo_entry = tk.Entry(repo_inner, textvariable=self.repo_path,
                              bg=COLORS["input_bg"], fg=COLORS["text"],
                              insertbackground=COLORS["text"],
                              font=("Segoe UI", 10), relief="flat",
                              bd=0, highlightthickness=1,
                              highlightcolor=COLORS["accent"],
                              highlightbackground=COLORS["border"])
        repo_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=6, padx=(0, 8))

        ttk.Button(repo_inner, text="Procurar...",
                   style="Secondary.TButton",
                   command=self._browse_repo).pack(side=tk.LEFT, padx=(0, 4))

        ttk.Button(repo_inner, text="Atualizar Branches",
                   style="Secondary.TButton",
                   command=self._refresh_branches).pack(side=tk.LEFT)

        # ─── Perfil + Source (lado a lado) ───
        config_frame = ttk.Frame(main, style="Dark.TFrame")
        config_frame.pack(fill=tk.X, pady=(0, 8))

        # Perfil
        profile_card = self._card(config_frame, "Perfil")
        profile_card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 5))
        profile_inner = ttk.Frame(profile_card, style="Card.TFrame")
        profile_inner.pack(fill=tk.X, padx=10, pady=(0, 10))

        profile_names = list(self.config.get("profiles", {}).keys())
        self.profile_combo = ttk.Combobox(profile_inner, values=profile_names,
                                          style="Dark.TCombobox", state="readonly")
        self.profile_combo.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 5))
        self.profile_combo.bind("<<ComboboxSelected>>", lambda e: self._load_profile())

        ttk.Button(profile_inner, text="Salvar",
                   style="Secondary.TButton",
                   command=self._save_profile).pack(side=tk.LEFT, padx=(0, 4))

        ttk.Button(profile_inner, text="+ Novo",
                   style="Secondary.TButton",
                   command=self._new_profile).pack(side=tk.LEFT, padx=(0, 4))

        ttk.Button(profile_inner, text="Excluir",
                   style="Secondary.TButton",
                   command=self._delete_profile).pack(side=tk.LEFT)

        # Source Branch — com busca
        source_card = self._card(config_frame, "Branch de Origem (source)")
        source_card.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(5, 0))
        source_inner = ttk.Frame(source_card, style="Card.TFrame")
        source_inner.pack(fill=tk.X, padx=10, pady=(0, 10))

        # Container para entry + listbox dropdown
        source_container = tk.Frame(source_inner, bg=COLORS["bg_card"])
        source_container.pack(fill=tk.X, expand=True)

        self.source_entry = tk.Entry(source_container,
                                     textvariable=self.source_branch,
                                     bg=COLORS["input_bg"], fg=COLORS["text"],
                                     insertbackground=COLORS["text"],
                                     font=("Segoe UI", 10), relief="flat",
                                     bd=0, highlightthickness=1,
                                     highlightcolor=COLORS["accent"],
                                     highlightbackground=COLORS["border"])
        self.source_entry.pack(fill=tk.X, ipady=6)

        # Listbox dropdown (escondida por padrão)
        self.source_listbox_frame = tk.Frame(source_container, bg=COLORS["border"])
        self.source_listbox = tk.Listbox(self.source_listbox_frame,
                                         bg=COLORS["input_bg"], fg=COLORS["text"],
                                         font=("Segoe UI", 10),
                                         selectbackground=COLORS["accent"],
                                         selectforeground="white",
                                         relief="flat", bd=0,
                                         highlightthickness=0,
                                         exportselection=False,
                                         height=8)
        self.source_listbox.pack(fill=tk.BOTH, expand=True, padx=1, pady=1)

        # Eventos de busca
        self.source_branch.trace_add("write", lambda *_: self._filter_source_list())
        self.source_entry.bind("<FocusIn>", lambda e: self._show_source_list())
        self.source_entry.bind("<FocusOut>", lambda e: self.root.after(150, self._hide_source_list))
        self.source_entry.bind("<Down>", lambda e: self._source_list_navigate(1))
        self.source_entry.bind("<Up>", lambda e: self._source_list_navigate(-1))
        self.source_entry.bind("<Return>", lambda e: self._source_list_select())
        self.source_entry.bind("<Escape>", lambda e: self._hide_source_list())
        self.source_listbox.bind("<ButtonRelease-1>", lambda e: self._source_list_click())

        # ─── Target Branches — TODAS do repo com busca ───
        target_outer = self._card(main, "Branches de Destino — selecione quais vao receber o merge")

        # Barra de busca + botões de seleção
        search_bar = ttk.Frame(target_outer, style="Card.TFrame")
        search_bar.pack(fill=tk.X, padx=10, pady=(0, 6))

        ttk.Label(search_bar, text="Filtrar:", style="Search.TLabel").pack(side=tk.LEFT, padx=(0, 6))

        self.search_entry = tk.Entry(search_bar, textvariable=self.search_var,
                                     bg=COLORS["input_bg"], fg=COLORS["text"],
                                     insertbackground=COLORS["text"],
                                     font=("Segoe UI", 10), relief="flat",
                                     bd=0, highlightthickness=1,
                                     highlightcolor=COLORS["accent"],
                                     highlightbackground=COLORS["border"])
        self.search_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=4, padx=(0, 8))
        self.search_var.trace_add("write", lambda *_: self._filter_branches())

        ttk.Button(search_bar, text="Todos",
                   style="Secondary.TButton",
                   command=lambda: self._toggle_visible(True)).pack(side=tk.LEFT, padx=(0, 4))
        ttk.Button(search_bar, text="Nenhum",
                   style="Secondary.TButton",
                   command=lambda: self._toggle_visible(False)).pack(side=tk.LEFT, padx=(0, 4))
        ttk.Button(search_bar, text="Carregar do Perfil",
                   style="Secondary.TButton",
                   command=self._select_from_profile).pack(side=tk.LEFT)

        # Canvas scrollável para as branches
        canvas_frame = tk.Frame(target_outer, bg=COLORS["bg_card"])
        canvas_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=(0, 10))

        self.branch_canvas = tk.Canvas(canvas_frame, bg=COLORS["bg_card"],
                                       highlightthickness=0, bd=0)
        scrollbar = ttk.Scrollbar(canvas_frame, orient=tk.VERTICAL,
                                  command=self.branch_canvas.yview)

        self.branch_scroll_frame = tk.Frame(self.branch_canvas, bg=COLORS["bg_card"])
        self.branch_scroll_frame.bind(
            "<Configure>",
            lambda e: self.branch_canvas.configure(scrollregion=self.branch_canvas.bbox("all"))
        )

        self.branch_canvas.create_window((0, 0), window=self.branch_scroll_frame, anchor="nw")
        self.branch_canvas.configure(yscrollcommand=scrollbar.set)

        self.branch_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        # Scroll com mouse
        def _on_mousewheel(event):
            self.branch_canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

        def _on_mousewheel_linux(event):
            if event.num == 4:
                self.branch_canvas.yview_scroll(-3, "units")
            elif event.num == 5:
                self.branch_canvas.yview_scroll(3, "units")

        self.branch_canvas.bind_all("<MouseWheel>", _on_mousewheel)
        self.branch_canvas.bind_all("<Button-4>", _on_mousewheel_linux)
        self.branch_canvas.bind_all("<Button-5>", _on_mousewheel_linux)

        # Definir altura fixa para o card de branches
        canvas_frame.configure(height=180)
        canvas_frame.pack_propagate(False)

        self._placeholder_targets()

        # ─── Status / contador ───
        self.status_label = ttk.Label(main, text="0 branches selecionadas",
                                      style="Subtitle.TLabel")
        self.status_label.pack(anchor=tk.W, pady=(0, 4))

        # ─── Botão Merge ───
        btn_frame = ttk.Frame(main, style="Dark.TFrame")
        btn_frame.pack(fill=tk.X, pady=(2, 8))

        self.merge_btn = ttk.Button(btn_frame,
                                    text="INICIAR MERGE",
                                    style="Accent.TButton",
                                    command=self._start_merge)
        self.merge_btn.pack(fill=tk.X, ipady=4)

        # ─── Log ───
        log_card = self._card(main, "Log de Execucao")
        log_card.pack(fill=tk.BOTH, expand=True)

        self.log_text = scrolledtext.ScrolledText(
            log_card, bg=COLORS["input_bg"], fg=COLORS["text"],
            font=("Consolas", 9), relief="flat", bd=0,
            insertbackground=COLORS["text"], wrap=tk.WORD,
            highlightthickness=0, padx=10, pady=8)
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=10, pady=(0, 10))

        # Tags de cor para o log
        self.log_text.tag_configure("success", foreground=COLORS["success"])
        self.log_text.tag_configure("error", foreground=COLORS["error"])
        self.log_text.tag_configure("warning", foreground=COLORS["warning"])
        self.log_text.tag_configure("info", foreground="#60a5fa")
        self.log_text.tag_configure("header", foreground=COLORS["accent"],
                                    font=("Consolas", 10, "bold"))

    def _card(self, parent, title):
        """Cria um card com título."""
        frame = tk.Frame(parent, bg=COLORS["bg_card"],
                         highlightbackground=COLORS["border"],
                         highlightthickness=1, bd=0)
        frame.pack(fill=tk.X, pady=(0, 8))

        ttk.Label(frame, text=title, style="CardTitle.TLabel").pack(
            anchor=tk.W, padx=12, pady=(10, 5))
        return frame

    def _placeholder_targets(self):
        """Mostra placeholder quando não há branches."""
        for w in self.branch_scroll_frame.winfo_children():
            w.destroy()
        lbl = tk.Label(self.branch_scroll_frame,
                       text="  Aponte para um repositorio e clique 'Atualizar Branches' para listar",
                       bg=COLORS["bg_card"], fg=COLORS["text_dim"],
                       font=("Segoe UI", 10))
        lbl.pack(padx=10, pady=15, anchor=tk.W)

    def _browse_repo(self):
        """Abre diálogo para selecionar repositório."""
        path = filedialog.askdirectory(title="Selecionar Repositorio Git")
        if path:
            self.repo_path.set(path)
            self.config["repo_path"] = path
            save_config(self.config)
            self._refresh_branches()

    def _refresh_branches(self):
        """Busca TODAS as branches do repositório e mostra como checkboxes."""
        repo = self.repo_path.get()
        if not repo or not os.path.isdir(repo):
            messagebox.showwarning("Aviso", "Selecione um repositorio valido.")
            return

        try:
            self._log("Buscando branches locais do repositorio...\n", "info")

            result = subprocess.run(
                ["git", "branch", "-a"],
                cwd=repo, capture_output=True, timeout=30,
                encoding="utf-8", errors="replace"
            )
            if result.returncode != 0:
                self._log(f"Erro: {(result.stderr or '')}\n", "error")
                return

            stdout = (result.stdout or "").strip()
            if not stdout:
                self._log("Nenhuma branch encontrada no repositorio.\n", "warning")
                return

            branches = []
            for line in stdout.split("\n"):
                branch = line.strip()
                if branch.startswith("* "):
                    branch = branch[2:]
                branch = branch.strip()
                if not branch or "HEAD" in branch:
                    continue
                if branch.startswith("remotes/origin/"):
                    branch = branch[len("remotes/origin/"):]
                if branch not in branches:
                    branches.append(branch)

            self.available_branches = sorted(branches, key=str.lower)
            # Atualizar a listbox de origem (será filtrada ao digitar)
            self._filter_source_list()

            # Guardar quais estavam selecionadas antes
            previously_selected = {b for b, v in self.all_branch_vars.items() if v.get()}

            # Criar checkboxes para TODAS as branches
            self._rebuild_branch_checkboxes(previously_selected)

            self._log(f"{len(self.available_branches)} branches encontradas\n", "success")

        except subprocess.TimeoutExpired:
            self._log("Timeout ao buscar branches\n", "error")
        except FileNotFoundError:
            self._log("Git nao encontrado. Verifique a instalacao.\n", "error")

    def _rebuild_branch_checkboxes(self, selected_set=None):
        """Reconstrói todos os checkboxes de branches."""
        if selected_set is None:
            selected_set = set()

        for w in self.branch_scroll_frame.winfo_children():
            w.destroy()
        self.all_branch_vars.clear()
        self._branch_widgets = {}

        if not self.available_branches:
            self._placeholder_targets()
            return

        for branch in self.available_branches:
            var = tk.BooleanVar(value=(branch in selected_set))
            self.all_branch_vars[branch] = var
            var.trace_add("write", lambda *_: self._update_status())

            cb_frame = tk.Frame(self.branch_scroll_frame, bg=COLORS["bg_card"])
            cb_frame.pack(fill=tk.X, padx=4, pady=1)

            cb = tk.Checkbutton(
                cb_frame, text=f"  {branch}",
                variable=var, bg=COLORS["bg_card"],
                fg=COLORS["text"], selectcolor=COLORS["input_bg"],
                activebackground=COLORS["bg_card"],
                activeforeground=COLORS["text"],
                font=("Segoe UI", 10),
                anchor="w", padx=8, pady=3,
                highlightthickness=0, bd=0)
            cb.pack(fill=tk.X)

            self._branch_widgets[branch] = cb_frame

        self._update_status()
        self._filter_branches()  # Aplicar visibilidade (só marcadas se sem filtro)

    # ─── Source branch search helpers ──────────────────────────────

    def _show_source_list(self):
        """Mostra a listbox de branches de origem."""
        self._filter_source_list()
        self.source_listbox_frame.pack(fill=tk.X, pady=(2, 0))

    def _hide_source_list(self):
        """Esconde a listbox."""
        self.source_listbox_frame.pack_forget()

    def _filter_source_list(self):
        """Filtra a listbox conforme o texto digitado."""
        query = self.source_branch.get().lower().strip()
        self.source_listbox.delete(0, tk.END)
        for branch in self.available_branches:
            if not query or query in branch.lower():
                self.source_listbox.insert(tk.END, branch)

    def _source_list_navigate(self, direction):
        """Navega na listbox com setas."""
        size = self.source_listbox.size()
        if size == 0:
            return
        sel = self.source_listbox.curselection()
        if sel:
            idx = sel[0] + direction
        else:
            idx = 0 if direction > 0 else size - 1
        idx = max(0, min(idx, size - 1))
        self.source_listbox.selection_clear(0, tk.END)
        self.source_listbox.selection_set(idx)
        self.source_listbox.see(idx)

    def _source_list_select(self):
        """Seleciona o item atual da listbox (Enter)."""
        sel = self.source_listbox.curselection()
        if sel:
            value = self.source_listbox.get(sel[0])
            self.source_branch.set(value)
            self._hide_source_list()
            self.root.focus_set()

    def _source_list_click(self):
        """Seleciona o item clicado na listbox."""
        sel = self.source_listbox.curselection()
        if sel:
            value = self.source_listbox.get(sel[0])
            self.source_branch.set(value)
            self._hide_source_list()
            self.root.focus_set()

    # ─── Target branch filter ───────────────────────────────────

    def _filter_branches(self):
        """Filtra branches: sem texto mostra só marcadas, com texto filtra todas."""
        query = self.search_var.get().lower().strip()
        for branch, frame in self._branch_widgets.items():
            is_selected = self.all_branch_vars[branch].get()
            if query:
                # Com filtro: mostra todas que batem com a busca
                visible = query in branch.lower()
            else:
                # Sem filtro: mostra apenas as marcadas
                visible = is_selected
            if visible:
                frame.pack(fill=tk.X, padx=4, pady=1)
            else:
                frame.pack_forget()

    def _toggle_visible(self, state):
        """Seleciona/desmarca apenas branches visíveis (filtradas)."""
        query = self.search_var.get().lower().strip()
        for branch, var in self.all_branch_vars.items():
            if not query or query in branch.lower():
                var.set(state)

    def _select_from_profile(self):
        """Marca apenas as branches que estão no perfil atual."""
        name = self.profile_combo.get()
        if not name:
            messagebox.showinfo("Info", "Selecione um perfil primeiro.")
            return

        profile = self.config.get("profiles", {}).get(name, {})
        profile_branches = set(profile.get("target_branches", []))

        for branch, var in self.all_branch_vars.items():
            var.set(branch in profile_branches)

        # Limpar filtro e mostrar só as marcadas
        self.search_var.set("")
        self._filter_branches()

    def _update_status(self):
        """Atualiza o contador de branches selecionadas."""
        count = sum(1 for v in self.all_branch_vars.values() if v.get())
        total = len(self.all_branch_vars)
        self.status_label.configure(
            text=f"{count} de {total} branches selecionadas para merge")

    def _load_profile(self):
        """Carrega um perfil de configuração."""
        name = self.profile_combo.get()
        if not name:
            return

        profiles = self.config.get("profiles", {})
        if name not in profiles:
            return

        profile = profiles[name]
        self.source_branch.set(profile.get("source_branch", ""))

        # Se já temos branches carregadas, marcar as do perfil
        if self.all_branch_vars:
            profile_branches = set(profile.get("target_branches", []))
            for branch, var in self.all_branch_vars.items():
                var.set(branch in profile_branches)

            # Limpar filtro e mostrar só as marcadas
            self.search_var.set("")
            self._filter_branches()

        self.config["last_profile"] = name
        save_config(self.config)

    def _save_profile(self):
        """Salva o perfil atual com as branches selecionadas."""
        name = self.profile_combo.get()
        if not name:
            messagebox.showwarning("Aviso", "Selecione ou crie um perfil primeiro.")
            return

        if "profiles" not in self.config:
            self.config["profiles"] = {}

        selected = [b for b, v in self.all_branch_vars.items() if v.get()]

        self.config["profiles"][name] = {
            "source_branch": self.source_branch.get(),
            "target_branches": selected
        }
        self.config["repo_path"] = self.repo_path.get()
        save_config(self.config)
        self._log(f"Perfil '{name}' salvo com {len(selected)} branches!\n", "success")

    def _new_profile(self):
        """Cria um novo perfil."""
        dialog = tk.Toplevel(self.root)
        dialog.title("Novo Perfil")
        dialog.geometry("400x150")
        dialog.configure(bg=COLORS["bg"])
        dialog.transient(self.root)
        dialog.grab_set()

        ttk.Label(dialog, text="Nome do perfil:",
                  style="Dark.TLabel").pack(padx=20, pady=(20, 5), anchor=tk.W)

        name_var = tk.StringVar()
        entry = tk.Entry(dialog, textvariable=name_var,
                         bg=COLORS["input_bg"], fg=COLORS["text"],
                         insertbackground=COLORS["text"],
                         font=("Segoe UI", 10), relief="flat",
                         highlightthickness=1,
                         highlightcolor=COLORS["accent"],
                         highlightbackground=COLORS["border"])
        entry.pack(padx=20, fill=tk.X, ipady=6)
        entry.focus_set()

        def create():
            name = name_var.get().strip()
            if not name:
                return
            if "profiles" not in self.config:
                self.config["profiles"] = {}
            self.config["profiles"][name] = {
                "source_branch": "",
                "target_branches": []
            }
            save_config(self.config)
            self.profile_combo["values"] = list(self.config["profiles"].keys())
            self.profile_combo.set(name)
            # Desmarcar tudo
            for var in self.all_branch_vars.values():
                var.set(False)
            dialog.destroy()

        ttk.Button(dialog, text="Criar", style="Accent.TButton",
                   command=create).pack(pady=15)
        entry.bind("<Return>", lambda e: create())

    def _delete_profile(self):
        """Exclui o perfil selecionado."""
        name = self.profile_combo.get()
        if not name:
            return
        if not messagebox.askyesno("Confirmar", f"Excluir perfil '{name}'?"):
            return

        self.config.get("profiles", {}).pop(name, None)
        save_config(self.config)
        self.profile_combo["values"] = list(self.config.get("profiles", {}).keys())
        self.profile_combo.set("")
        self._log(f"Perfil '{name}' excluido.\n", "warning")

    def _log(self, msg, tag=None):
        """Adiciona mensagem ao log."""
        self.log_text.configure(state=tk.NORMAL)
        if tag:
            self.log_text.insert(tk.END, msg, tag)
        else:
            self.log_text.insert(tk.END, msg)
        self.log_text.see(tk.END)

    def _run_git(self, args, cwd=None):
        """Executa comando git e retorna (sucesso, stdout, stderr)."""
        repo = cwd or self.repo_path.get()
        try:
            result = subprocess.run(
                ["git"] + args,
                cwd=repo, capture_output=True, timeout=120,
                encoding="utf-8", errors="replace"
            )
            stdout = (result.stdout or "").strip()
            stderr = (result.stderr or "").strip()
            return result.returncode == 0, stdout, stderr
        except subprocess.TimeoutExpired:
            return False, "", "Timeout ao executar comando"
        except Exception as e:
            return False, "", str(e)

    def _checkout_branch(self, branch):
        """Tenta checkout de uma branch. Se só existe no remoto, cria local tracking."""
        ok, out, err = self._run_git(["checkout", branch])
        if ok:
            return True, out, err
        # Tentar criar branch local a partir da remota
        ok2, out2, err2 = self._run_git(["checkout", "-b", branch, f"origin/{branch}"])
        if ok2:
            return True, out2, err2
        # Retornar o erro original
        return False, out, err

    def _start_merge(self):
        """Inicia o processo de merge em thread separada."""
        if self.is_running:
            return

        repo = self.repo_path.get()
        source = self.source_branch.get()
        targets = [b for b, v in self.all_branch_vars.items() if v.get()]

        if not repo:
            messagebox.showwarning("Aviso", "Selecione um repositorio.")
            return
        if not source:
            messagebox.showwarning("Aviso", "Selecione a branch de origem.")
            return
        if not targets:
            messagebox.showwarning("Aviso", "Selecione pelo menos uma branch de destino.")
            return

        # Confirmação
        branch_list = "\n".join(f"  -> {t}" for t in targets)
        msg = (f"Merge da branch '{source}' para {len(targets)} branches:\n\n"
               f"{branch_list}\n\nDeseja continuar?")
        if not messagebox.askyesno("Confirmar Merge", msg):
            return

        self.is_running = True
        self.merge_btn.configure(state="disabled")
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.delete("1.0", tk.END)

        thread = threading.Thread(target=self._execute_merge,
                                  args=(repo, source, targets), daemon=True)
        thread.start()

    def _execute_merge(self, repo, source, targets):
        """Executa o merge (roda em thread separada)."""
        results = {"success": [], "failed": []}

        self._log("=" * 60 + "\n", "header")
        self._log(f"  INICIANDO MERGE: {source} -> {len(targets)} branches\n", "header")
        self._log("=" * 60 + "\n\n", "header")

        # 1. Checkout na source branch
        self._log(f"[1/2] Checkout na branch de origem: {source}\n", "info")
        ok, _, err = self._checkout_branch(source)
        if not ok:
            self._log(f"  ERRO ao checkout '{source}': {err}\n", "error")
            self._finish_merge(results)
            return
        self._log(f"  Branch '{source}' OK\n", "success")

        # 2. Merge em cada target
        self._log(f"\n[2/2] Iniciando merges...\n\n", "info")

        for i, target in enumerate(targets, 1):
            self._log(f"{'─' * 50}\n")
            self._log(f"  [{i}/{len(targets)}] {source} -> {target}\n", "header")
            self._log(f"{'─' * 50}\n")

            # Checkout target
            self._log(f"  Checkout: {target}...", "info")
            ok, _, err = self._checkout_branch(target)
            if not ok:
                self._log(f" FALHOU\n", "error")
                self._log(f"    {err}\n", "error")
                results["failed"].append((target, f"Checkout falhou: {err}"))
                continue
            self._log(f" OK\n", "success")

            # Merge
            self._log(f"  Merge: {source} -> {target}...", "info")
            ok, out, err = self._run_git(["merge", source, "--no-edit"])

            if ok:
                self._log(f" SUCESSO\n", "success")

                # Perguntar sobre push
                self._ask_push(target, results)
            else:
                if "CONFLICT" in (err + out):
                    self._log(f" CONFLITO\n", "error")
                    self._log(f"    Conflitos detectados. Abortando merge.\n", "error")
                    self._run_git(["merge", "--abort"])
                    self._log(f"    Resolva manualmente: git checkout {target} && git merge {source}\n", "warning")
                    results["failed"].append((target, "Conflitos de merge"))
                else:
                    self._log(f" ERRO\n", "error")
                    self._log(f"    {err}\n", "error")
                    self._run_git(["merge", "--abort"])
                    results["failed"].append((target, err))

            self._log("\n")

        # Voltar para source
        self._log(f"Voltando para: {source}\n", "info")
        self._run_git(["checkout", source])

        self._finish_merge(results)

    def _ask_push(self, branch, results):
        """Pergunta sobre push (roda na thread principal)."""
        response = [None]
        event = threading.Event()

        def ask():
            resp = messagebox.askyesno(
                "Push",
                f"Merge em '{branch}' concluido!\n\nFazer push para origin/{branch}?")
            response[0] = resp
            event.set()

        self.root.after(0, ask)
        event.wait()

        if response[0]:
            self._log(f"  Push: origin/{branch}...", "info")
            ok, out, err = self._run_git(["push", "origin", branch])
            if ok:
                self._log(f" OK\n", "success")
                results["success"].append(branch)
            else:
                self._log(f" FALHOU: {err}\n", "error")
                results["failed"].append((branch, f"Push falhou: {err}"))
        else:
            self._log(f"  Push ignorado para {branch}\n", "warning")
            results["success"].append(f"{branch} (sem push)")

    def _finish_merge(self, results):
        """Exibe resumo final."""
        self._log(f"\n{'=' * 60}\n", "header")
        self._log("  RESUMO FINAL\n", "header")
        self._log(f"{'=' * 60}\n\n", "header")

        if results["success"]:
            self._log("  Sucesso:\n", "success")
            for b in results["success"]:
                self._log(f"     [OK] {b}\n", "success")

        if results["failed"]:
            self._log("\n  Falhas:\n", "error")
            for b, reason in results["failed"]:
                self._log(f"     [FALHOU] {b}: {reason}\n", "error")

        total = len(results["success"]) + len(results["failed"])
        self._log(f"\n  Total: {len(results['success'])}/{total} branches mergeadas com sucesso\n\n")

        self.is_running = False
        self.root.after(0, lambda: self.merge_btn.configure(state="normal"))

        # Notificação
        def notify():
            if results["failed"]:
                messagebox.showwarning(
                    "Merge Concluido",
                    f"{len(results['success'])}/{total} sucesso, {len(results['failed'])} falhas.\n"
                    f"Verifique o log para detalhes.")
            else:
                messagebox.showinfo(
                    "Merge Concluido",
                    f"Todas as {total} branches mergeadas com sucesso!")
        self.root.after(0, notify)


def main():
    root = tk.Tk()

    try:
        root.iconbitmap(default="")
    except Exception:
        pass

    # Centralizar janela
    root.update_idletasks()
    w, h = 960, 800
    x = (root.winfo_screenwidth() - w) // 2
    y = (root.winfo_screenheight() - h) // 2
    root.geometry(f"{w}x{h}+{x}+{y}")

    app = GitMergeTool(root)
    root.mainloop()


if __name__ == "__main__":
    main()
