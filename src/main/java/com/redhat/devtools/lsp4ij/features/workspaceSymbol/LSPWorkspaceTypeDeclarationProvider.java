/*******************************************************************************
 * Copyright (c) 2024 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/

package com.redhat.devtools.lsp4ij.features.workspaceSymbol;

import com.intellij.codeInsight.navigation.actions.TypeDeclarationPlaceAwareProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.containers.ContainerUtil;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.client.ExecuteLSPFeatureStatus;
import com.redhat.devtools.lsp4ij.client.indexing.ProjectIndexingManager;
import com.redhat.devtools.lsp4ij.features.LSPPsiElementFactory;
import com.redhat.devtools.lsp4ij.features.typeDefinition.LSPTypeDefinitionParams;
import com.redhat.devtools.lsp4ij.features.typeDefinition.LSPTypeDefinitionSupport;
import org.eclipse.lsp4j.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.isDoneNormally;
import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.waitUntilDone;

/**
 * Implements the IDE's standard Go To Type Declaration action using LSP textDocument/typeDefinition. -->
 */
public class LSPWorkspaceTypeDeclarationProvider implements TypeDeclarationPlaceAwareProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LSPWorkspaceTypeDeclarationProvider.class);

    @Override
    public PsiElement @Nullable [] getSymbolTypeDeclarations(@NotNull PsiElement symbol) {
        // Not much we can do without an offset
        return PsiElement.EMPTY_ARRAY;
    }

    @Override
    public PsiElement @Nullable [] getSymbolTypeDeclarations(@NotNull PsiElement symbol, Editor editor, int offset) {
        if (!symbol.isValid()) {
            return PsiElement.EMPTY_ARRAY;
        }

        Project project = symbol.getProject();
        if (project.isDisposed()) {
            return PsiElement.EMPTY_ARRAY;
        }

        PsiFile file = symbol.getContainingFile();
        if ((file == null) || !file.isValid()) {
            return PsiElement.EMPTY_ARRAY;
        }

        if (ProjectIndexingManager.canExecuteLSPFeature(file) != ExecuteLSPFeatureStatus.NOW) {
            return PsiElement.EMPTY_ARRAY;
        }

        if (!LanguageServiceAccessor.getInstance(project)
                .hasAny(file.getVirtualFile(), ls -> ls.getClientFeatures().getTypeDefinitionFeature().isTypeDefinitionSupported(file))) {
            return PsiElement.EMPTY_ARRAY;
        }

        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            return PsiElement.EMPTY_ARRAY;
        }

        LSPTypeDefinitionSupport typeDefinitionSupport = LSPFileSupport.getSupport(file).getTypeDefinitionSupport();
        var params = new LSPTypeDefinitionParams(LSPIJUtils.toTextDocumentIdentifier(file.getVirtualFile()), LSPIJUtils.toPosition(offset, document), offset);
        CompletableFuture<List<Location>> typeDefinitionsFuture = typeDefinitionSupport.getTypeDefinitions(params);
        try {
            waitUntilDone(typeDefinitionsFuture, file);
        } catch (ProcessCanceledException ex) {
            // cancel the LSP requests textDocument/typeDefinition
            typeDefinitionSupport.cancel();
        } catch (CancellationException ex) {
            // cancel the LSP requests textDocument/typeDefinition
            typeDefinitionSupport.cancel();
        } catch (ExecutionException e) {
            LOGGER.error("Error while consuming LSP 'textDocument/typeDefinition' request", e);
        }

        if (isDoneNormally(typeDefinitionsFuture)) {
            List<Location> typeDefinitions = typeDefinitionsFuture.getNow(null);
            if (!ContainerUtil.isEmpty(typeDefinitions)) {
                // textDocument/typeDefinition has been collected correctly
                List<PsiElement> typeDefinitionElements = new ArrayList<>(typeDefinitions.size());
                for (Location typeDefinition : typeDefinitions) {
                    ContainerUtil.addIfNotNull(typeDefinitionElements, LSPPsiElementFactory.toPsiElement(typeDefinition, project));
                }
                return typeDefinitionElements.toArray(PsiElement.EMPTY_ARRAY);
            }
        }

        return PsiElement.EMPTY_ARRAY;
    }
}
