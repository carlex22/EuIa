// File: euia/ui/RegistrationScreen.kt
package com.carlex.euia.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.carlex.euia.AppDestinations // Importar AppDestinations para as rotas
import com.carlex.euia.R // Importar R para recursos de string
import com.carlex.euia.viewmodel.AuthViewModel // Importar o AuthViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(
    navController: NavController,
    authViewModel: AuthViewModel = viewModel() // ViewModel é injetado ou fornecido por padrão
) {
    val emailState = remember { mutableStateOf("") }
    val passwordState = remember { mutableStateOf("") }
    val confirmPasswordState = remember { mutableStateOf("") }

    val emailError = remember { mutableStateOf<String?>(null) }
    val passwordError = remember { mutableStateOf<String?>(null) }
    val confirmPasswordError = remember { mutableStateOf<String?>(null) }

    val isLoading by authViewModel.isLoading.collectAsState() // Observa o estado de carregamento do ViewModel
    val authError by authViewModel.error.collectAsState()     // Observa mensagens de erro do ViewModel
    val currentUser by authViewModel.currentUser.collectAsState() // Observa o usuário logado após o registro

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current // Usado para acessar strings de recursos

    // Observa o usuário logado. Se um usuário for criado/logado, navega para a tela principal.
    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.registration_success_message),
                    duration = SnackbarDuration.Short
                )
            }
            // Navega para a tela principal (Project Management) e limpa a pilha de autenticação
            navController.navigate(AppDestinations.PROJECT_MANAGEMENT_ROUTE) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    // Observa erros do ViewModel e os exibe no Snackbar
    LaunchedEffect(authError) {
        authError?.let { message ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Long
                )
            }
            // Opcional: Se o ViewModel tiver um método clearError(), chame-o aqui para evitar reexibições
            // authViewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f) // Ocupa 90% da largura da tela
                    .verticalScroll(rememberScrollState()) // Permite rolagem se o teclado aparecer
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Logo do aplicativo
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.content_desc_app_logo),
                    modifier = Modifier.size(120.dp)
                )
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.registration_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(32.dp))

                // Campo de E-mail
                OutlinedTextField(
                    value = emailState.value,
                    onValueChange = {
                        emailState.value = it
                        emailError.value = null // Limpa erro ao digitar
                    },
                    label = { Text(stringResource(R.string.registration_email_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = stringResource(R.string.content_desc_email_icon))
                    },
                    isError = emailError.value != null,
                    supportingText = { if (emailError.value != null) Text(emailError.value!!) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Campo de Senha
                OutlinedTextField(
                    value = passwordState.value,
                    onValueChange = {
                        passwordState.value = it
                        passwordError.value = null // Limpa erro ao digitar
                    },
                    label = { Text(stringResource(R.string.registration_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.content_desc_password_icon))
                    },
                    isError = passwordError.value != null,
                    supportingText = { if (passwordError.value != null) Text(passwordError.value!!) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Campo de Confirmar Senha
                OutlinedTextField(
                    value = confirmPasswordState.value,
                    onValueChange = {
                        confirmPasswordState.value = it
                        confirmPasswordError.value = null // Limpa erro ao digitar
                    },
                    label = { Text(stringResource(R.string.registration_confirm_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.content_desc_confirm_password_icon))
                    },
                    isError = confirmPasswordError.value != null,
                    supportingText = { if (confirmPasswordError.value != null) Text(confirmPasswordError.value!!) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Botão de Registrar
                Button(
                    onClick = {
                        // Limpa todos os erros antes de uma nova validação
                        emailError.value = null
                        passwordError.value = null
                        confirmPasswordError.value = null

                        if (emailState.value.isBlank()) {
                            emailError.value = context.getString(R.string.registration_email_empty_error)
                            return@Button
                        }
                        if (passwordState.value.isBlank()) {
                            passwordError.value = context.getString(R.string.registration_password_empty_error)
                            return@Button
                        }
                        if (passwordState.value.length < 6) {
                            passwordError.value = context.getString(R.string.registration_password_min_length_error)
                            return@Button
                        }
                        if (confirmPasswordState.value.isBlank()) {
                            confirmPasswordError.value = context.getString(R.string.registration_confirm_password_empty_error)
                            return@Button
                        }
                        if (passwordState.value != confirmPasswordState.value) {
                            confirmPasswordError.value = context.getString(R.string.registration_password_mismatch_error)
                            return@Button
                        }

                        // Se todas as validações passarem, chama o ViewModel
                        authViewModel.register(emailState.value, passwordState.value)
                    },
                    enabled = !isLoading, // Desabilita o botão enquanto o registro está em andamento
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.registration_button_register))
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Link para Login
                OutlinedButton(
                    onClick = {
                        navController.navigate(AppDestinations.LOGIN_ROUTE) {
                            popUpTo(AppDestinations.REGISTER_ROUTE) { inclusive = true } // Remove a tela de registro da pilha
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.registration_button_already_have_account))
                }
            }
        }
    }
}